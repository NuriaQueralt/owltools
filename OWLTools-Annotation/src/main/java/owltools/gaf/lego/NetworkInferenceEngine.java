package owltools.gaf.lego;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.log4j.Logger;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationProperty;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLIndividual;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLNamedObject;
import org.semanticweb.owlapi.model.OWLObject;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLObjectPropertyAssertionAxiom;
import org.semanticweb.owlapi.model.OWLObjectPropertyExpression;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.OWLPropertyExpression;
import org.semanticweb.owlapi.vocab.OWLRDFVocabulary;

import owltools.gaf.ExtensionExpression;
import owltools.gaf.GafDocument;
import owltools.gaf.GeneAnnotation;
import owltools.graph.OWLGraphWrapper;

/**
 * Generates a Ontological Functional Network (aka LEGO graph) for a process given an ontology and a set of annotations
 * 
 * The input is a GO BP class and a set of genes (e.g. all genes involved in that BP), construct the most likely
 * set of gene + activity-type (i.e. MF) pairs that are likely to be executed during this process.
 * Also infer causal relationships between gene-activity pairs based on PPI networks, annotation extensions.
 * 
 *  Finally, break the process into chunks, e.g. using has_part links.
 *  
 *  <h2>Definitions</h2>
 *  
 *  <ul>
 *  <li> G : set of genes/products (for a whole genome/organism)
 *  <li> T : set of all ontology classes and object properties
 *  <li> T<sup>A</sup> : all ontology classes in MF (activity) ontology
 *  <li> T<sup>P</sup> : all ontology classes in BP ontology
 *  <li> A : set of all activity instances in a functional network. Each activity is a gene / activity-class pair. ie A &sube; G x T<sup>A</sup>
 *  <li> E : Optionally typed connections between activity instances. ie E &sube; A x A x T<sup>Rel</sup>
 *  <li> P : Set of all process instances. P &sube; T<sup>P</sup>
 *  <li> M : Merelogy (partonomy), from activity instances to process instances, and between process instances.  ie M &sube; A &cup; P x P
 *  </ul>
 *  
 *  <h2>Algorithm</h2>
 *  
 *  <ul>
 *  <li> {@link #seedGraph} - seed initial A
 *  <li> {@link #createPartonomy} - create partonomy P
 *  <li> {@link #connectGraph} - created activity network E
 *  </ul>
 *  
 *  <h2>TODO</h2>
 *  
 *  <ul>
 *  <li>Convert to LEGO
 *  <li>Rules for inferring occurs_in (requires extending A to be A &sube; G x T<sup>A</sup> x T<sup>C</sup>)
 *  </ul>
 *  
 *
 */
public class NetworkInferenceEngine {

	private static Logger LOG = Logger.getLogger(NetworkInferenceEngine.class);

	/**
	 * E<sup>A</sup> &sube; A x A x T<sup>A</sup>
	 */
	public ActivityNetwork activityNetwork;
	Map<String,Set<String>> proteinInteractionMap;
	Set<String> populationGeneSet;
	Map<Object,String> labelMap; 
	Map<OWLClass,Set<String>> geneByInferredClsMap;
	HashMap<String, Set<OWLClass>> clsByGeneMap;
	Set<OWLClass> activityClassSet;
	public Set<OWLClass> processClassSet;
	/**
	 * M &sube; N x N, N = A &cup; P
	 */
	public Partonomy partonomy;
	Set<Process> processSet; // P : process *instances*
	OWLGraphWrapper ogw;
	OWLOntology exportOntology; // destination for OWL/lego. may be refactored into separate class
	public OWLOntologyManager owlOntologyManager;

	String contextId = ""; // TODO

	private IRI createIRI(String... toks) {
		String id = toks.toString();
		return IRI.create("http://x.org"+id);
	}

	// N = A &cup; P
	// note: each node wraps an OWLObject
	abstract class InstanceNode {
		public OWLIndividual owlObject;
	}

	/**
	 * A &sube; G x T<sup>A</sup>
	 * </br>
	 * An instance of an activity that is enabled by some gene/product
	 */
	public class Activity extends InstanceNode {
		public Activity(OWLClassExpression a, String g, String context) {
			activityClass = a;
			gene = g;
			String acid = "";
			if (a != null) {
				acid = ogw.getIdentifier(a);
			}
			IRI iri = createIRI(context, acid, g);
			owlObject = getOWLDataFactory().getOWLNamedIndividual(iri);
		}
		private void addAxioms() {
			OWLClassExpression c = activityClass;
			if (c == null)
				c = ogw.getOWLClassByIdentifier("GO:0003674"); // TODO - use vocab
			addAxiom(getOWLDataFactory().getOWLClassAssertionAxiom(
					c, owlObject));
			if (gene != null) {
				OWLClassExpression x = getOWLDataFactory().getOWLObjectSomeValuesFrom(
						getObjectProperty("enabled_by"),
						getOWLClass(gene)); // TODO <-- protein IRI should be here
				addAxiom(getOWLDataFactory().getOWLClassAssertionAxiom(
						x,
						owlObject));	
			}
		}
		public final OWLClassExpression activityClass;
		public final String gene;
		public Double strength;		
	}

	public class Process extends InstanceNode {
		public final OWLClassExpression owlClassExpression;
		public Process(OWLClassExpression pc, String context) {
			owlClassExpression = pc;
			IRI iri = createIRI(context, pc.toString()); // TODO
			owlObject = getOWLDataFactory().getOWLNamedIndividual(iri);
			addAxioms();		

		}
		private void addAxioms() {
			addAxiom(getOWLDataFactory().getOWLClassAssertionAxiom(
					owlClassExpression,
					owlObject));
		}
	}

	/**
	 * 
	 * </br>
	 * EdgeType can be null for unknown (i.e. those derived from
	 * PPIs)
	 * @param <T> 
	 *
	 */
	public class Edge<T extends InstanceNode,U extends InstanceNode> {
		public final T subject;
		public final U object;
		public final OWLObjectPropertyExpression type;
		public OWLObjectPropertyAssertionAxiom owlObject;

		/**
		 * @param subject
		 * @param object
		 * @param type
		 */
		public Edge(T subject, U object, OWLObjectPropertyExpression type) {
			super();
			this.subject = subject;
			this.object = object;
			this.type = type;
			// todo - add to owl ontology
		}
		public Edge(T subject, U object, String type) {
			super();
			this.subject = subject;
			this.object = object;
			this.type = getObjectProperty(type);
			// todo - add to owl ontology
			addAxioms();
		}
		private void addAxioms() {
			// note: type may change with time so we may want to defer...?
			owlObject = 
					getOWLDataFactory().getOWLObjectPropertyAssertionAxiom(
							type,
							subject.owlObject,
							object.owlObject);

		}
	}
	/*
	public class ActivityEdge extends Edge<Activity,Activity> {
		public ActivityEdge(Activity subject, Activity object, String type) {
			super(subject, object, type);
		}
	}
	 */

	/**
	 * N<sup>A</sup> = (A, E<sup>A</sup>)
	 * </br>
	 * A network/graph of activity nodes
	 */
	public class ActivityNetwork {
		public Set<Activity> activitySet = new HashSet<Activity>();
		public Set<Edge<Activity,Activity>> activityEdgeSet = new HashSet<Edge<Activity,Activity>>();
		public void add(Activity a) {
			activitySet.add(a);
			a.addAxioms();
		}
		public void addEdge(Activity s, Activity o, OWLObjectProperty type) {
			activityEdgeSet.add(new Edge(s, o, type));
		}
		public Set<Activity> lookupByGene(String g) {
			Set<Activity> activitySubset = new HashSet<Activity>();
			for (Activity a : activitySet) {
				if (a.gene == null)
					continue;
				if (a.gene.equals(g)) {
					activitySubset.add(a);
				}
			}
			return activitySubset;
		}
		public Set<Activity> lookupByActivityType(OWLClassExpression t) {
			Set<Activity> activitySubset = new HashSet<Activity>();
			for (Activity a : activitySet) {
				if (a.activityClass == null)
					continue;
				if (a.activityClass.equals(t)) {
					activitySubset.add(a);
				}
			}
			return activitySubset;
		}
	}

	/**
	 * M : Merelogy (partonomy), from activity instances to process instances, and between process instances.  ie M &sube; A &cup; P x P
	 */
	public class Partonomy {
		public Set<Edge<InstanceNode, InstanceNode>> edgeSet = new HashSet<Edge<InstanceNode, InstanceNode>>();
		public void addEdge(InstanceNode s, InstanceNode o) {
			edgeSet.add(new Edge<InstanceNode,InstanceNode>(s, o, "part_of")); // TODO - use vocab
		}

	}

	/**
	 * Performs all steps to build activation network
	 * 
	 * @param processCls
	 * @param seedGenes
	 * @throws OWLOntologyCreationException 
	 */
	public void buildNetwork(OWLClass processCls, Set<String> seedGenes) throws OWLOntologyCreationException {
		seedGraph(processCls, seedGenes);
		createPartonomy(processCls);
		connectGraph();
	}
	public void buildNetwork(String processClsId, Set<String> seedGenes) throws OWLOntologyCreationException {
		OWLClass processCls = ogw.getOWLClassByIdentifier(processClsId);
		buildNetwork(processCls, seedGenes);
	}

	/**
	 * Create initial activation node set A for a process P and a set of seed genes
	 * 
	 * for all g &in; G<sup>seed</sup>, add a = <g,t> to A where f = argmax(p) { t :  t &in; T<sup>A</sup>, p=Prob( t | g) } 
	 * 
	 * @param processCls
	 * @param seedGenes
	 * @throws OWLOntologyCreationException 
	 */
	public void seedGraph(OWLClass processCls, Set<String> seedGenes) throws OWLOntologyCreationException {

		contextId = ogw.getIdentifier(processCls); // TODO

		IRI ontIRI = this.getIRI("TEMP:" + contextId);
		LOG.info("ONT IRI = "+ontIRI);
		exportOntology = getOWLOntologyManager().createOntology(ontIRI);

		activityNetwork = new ActivityNetwork();
		for (String g : seedGenes) {
			Activity a = getMostLikelyActivityForGene(g, processCls);
			activityNetwork.add(a);
		}
	}
	public void seedGraph(String processClsId, Set<String> seedGenes) throws OWLOntologyCreationException {
		OWLClass processCls = ogw.getOWLClassByIdentifier(processClsId);
		seedGraph(processCls, seedGenes);
	}

	/**
	 * @see seedGraph(String p, Set seed)
	 * @param processCls
	 * @throws OWLOntologyCreationException 
	 */
	public void seedGraph(OWLClass processCls) throws OWLOntologyCreationException {
		seedGraph(processCls, getGenes(processCls));
	}

	/**
	 * Generate M = N x N where N &in; P or N &in; A
	 * 
	 * Basic idea: we want to create a partonomy that breaks down a large process into smaller chunks and ultimately partonomic leaves - activities.
	 * This partonomy may not be identical to the GO partonomy - each node is an instance in the context of the larger process.
	 * 
	 * As a starting point we have a set of leaves - candidate activations we suspect to be involved somehow in the larger process.
	 * We also have knowledge in the ontology - both top-down (e.g. W has_part some P) and bottom-up (e.g. P part_of some W). We want to
	 * connect the leaves to the roots through intermediates. 
	 * 
	 * 
	 * @param processCls
	 */
	public void createPartonomy(OWLClass processCls) {
		processSet = new HashSet<Process>();
		partonomy = new Partonomy();
		String contextId = ogw.getIdentifier(processCls); // TODO

		Process rootProcess = new Process(processCls, contextId);

		OWLPropertyExpression HP = ogw.getOWLObjectPropertyByIdentifier("BFO:0000051");
		//ps = new HashSet<OWLPropertyExpression>();
		Set<OWLObject> partClasses = ogw.getAncestors(processCls, Collections.singleton(HP));
		Set<OWLObject> partClassesRedundant = new HashSet<OWLObject>();
		Set<Activity> activitiesWithoutParents = new HashSet<Activity>(activityNetwork.activitySet);
		for (OWLObject part : partClasses) {
			// loose redundancy - superclasses only
			partClassesRedundant.addAll(ogw.getAncestors(part, new HashSet<OWLPropertyExpression>()));
		}
		// must have has_part in chain; TODO - more elegant way of doing this
		partClassesRedundant.addAll(ogw.getAncestors(processCls, Collections.EMPTY_SET));
		for (OWLObject partClass : partClasses) {
			if (partClassesRedundant.contains(partClass))
				continue;
			Process partProcess = new Process((OWLClass)partClass, contextId);

			// The part is either an Activity (i.e. partonomy leaf node) or a Process instance
			if (this.activityClassSet.contains(partClass)) {
				// the part is an MF class - make a new Activity
				// TODO - check - reuse existing if present?
				LOG.info("NULL ACTIVITY="+processCls + " h-p "+partClass);
				Activity a = new Activity((OWLClass)partClass, null, contextId);
				activityNetwork.add(a);
			}
			else if (this.processClassSet.contains(partClass)) {
				boolean isIntermediate = false;
				// for now, only add "intermediates" - revise later? post-prune?
				// todo - intermediates within process part of partonomy
				for (Activity a : activityNetwork.activitySet) {
					if (a.activityClass == null)
						continue;
					OWLObject ac = a.activityClass;
					if (ogw.getAncestors(ac).contains(partClass)) {
						isIntermediate = true;
						partonomy.addEdge( a, partProcess);
						activitiesWithoutParents.remove(a);
						processSet.add(partProcess);
					}
				}
				if (isIntermediate) {
					LOG.info("INTERMEDIATE PROCESS="+processCls + " h-p "+partClass);
					partonomy.addEdge(partProcess, rootProcess);
				}
			}
		}

		// TODO - for now we leave it as implicit that every member a of A is in P = a x p<sup>seed</sup>
		for (Activity a : activitiesWithoutParents) {
			if (a.activityClass != null)
				partonomy.addEdge(a, rootProcess);
		}

	}

	/**
	 * Add default edges based on PPI network
	 *  
	 * add ( a<sub>1</sub> , a<sub>2</sub> ) to E
	 * where ( g<sub>1</sub> , g<sub>2</sub> ) is in PPI, and
	 * a = (g, _) is in A
	 * 
	 */
	public void connectGraph() {

		// PPI Method
		for (String p1 : proteinInteractionMap.keySet()) {
			Set<Activity> aset = activityNetwork.lookupByGene(p1);
			if (aset.size() == 0)
				continue;
			for (String p2 : proteinInteractionMap.get(p1)) {
				Set<Activity> aset2 = activityNetwork.lookupByGene(p2);
				for (Activity a1 : aset) {
					for (Activity a2 : aset2) {
						activityNetwork.addEdge(a1, a2, null);
					}
				}
			}
		}

		// Using ontology knowledge (e.g. connected_to relationships; has_input = has_output)

		for (Activity a : activityNetwork.activitySet) {
			//
		}

		// Annotation extension method
		// TODO: e.g. PomBase SPCC645.07      rgf1            GO:0032319-regulation of Rho GTPase activity    PMID:16324155   IGI     PomBase:SPAC1F7.04      P       RhoGEF for Rho1, Rgf1           protein taxon:4896      20100429  PomBase 
		// in: =GO:0051666 ! actin cortical patch localization
	}


	/**
	 * @param g
	 * @param processCls
	 * @return
	 */
	public Activity getMostLikelyActivityForGene(String g, OWLClass processCls) {
		Double bestPr = null;
		Activity bestActivity = new Activity(null, g, contextId); // todo
		for (OWLClass activityCls : getMostSpecificActivityTypes(g)) {
			//Double pr = this.calculateConditionalProbaility(processCls, activityCls);
			Double pr = this.calculateConditionalProbaility(activityCls, processCls);
			if (bestPr == null || pr >= bestPr) {
				Activity a = new Activity(activityCls, g, contextId);
				a.strength = pr;
				bestActivity = a;
				bestPr = pr;
			}
		}
		return bestActivity;
	}



	/**
	 * 
	 * Pr( F | P ) = Pr(F,P) / Pr(P)
	 * 
	 */

	public Double calculateConditionalProbaility(OWLClass wholeCls, OWLClass partCls) {
		Set<String> wgs = getGenes(wholeCls);
		Set<String> cgs = getGenes(partCls);
		cgs.retainAll(wgs);
		double n = getNumberOfGenes();
		Double pr = (cgs.size() / n) / ( wgs.size() / n);		
		return pr;
	}


	/**
	 * Get all activity types a gene enables (i.e. direct MF annotations)
	 * @param g
	 * @return { t : t &in; T<sup>A</sup>, g x t &in; Enables }
	 */
	public Set<OWLClass> getActivityTypes(String g) {
		HashSet<OWLClass> cset = new HashSet<OWLClass>(clsByGeneMap.get(g));
		cset.retainAll(activityClassSet);
		return cset;
	}


	/**
	 * @param g
	 * @return { t : t &in; getActivityTypes(g), &not; &Exists; t' : t' &in; getActivityTypes(g), t' ProperInferredßSubClassOf t }
	 */
	public Set<OWLClass>  getMostSpecificActivityTypes(String g) {
		Set<OWLClass> cset = getActivityTypes(g);
		removeRedundant(cset);
		return cset;
	}

	private void removeRedundant(Set<OWLClass> cset) {
		Set<OWLClass> allAncs = new HashSet<OWLClass>();
		for (OWLClass c : cset) {
			Set<OWLObject> ancClsSet = ogw.getNamedAncestors(c);
			for (OWLObject obj : ancClsSet) {
				allAncs.add((OWLClass) obj);
			}
		}
		cset.removeAll(allAncs);
	}

	/**
	 * Gets all genes that enable a given activity type (i.e. inverred annotations to MF term)
	 * @param t
	 * @return { g : g x t &in; InferredInvolvedIn }
	 */
	public Set<String> getGenes(OWLClass wholeCls) {
		if (!geneByInferredClsMap.containsKey(wholeCls)) {
			LOG.info("Nothing known about "+wholeCls);
			return new HashSet<String>();
		}
		return new HashSet<String>(geneByInferredClsMap.get(wholeCls));
	}

	/**
	 * @return |G|
	 */
	public int getNumberOfGenes() {
		return populationGeneSet.size();
	}

	public void initialize(GafDocument gafdoc, OWLGraphWrapper g) {
		ogw = g;
		geneByInferredClsMap = new HashMap<OWLClass,Set<String>>();
		populationGeneSet = new HashSet<String>();
		clsByGeneMap = new HashMap<String,Set<OWLClass>>();
		labelMap = new HashMap<Object,String>();
		proteinInteractionMap = new HashMap<String,Set<String>>();
		for (GeneAnnotation ann : gafdoc.getGeneAnnotations()) {
			String c = ann.getCls();
			OWLClass cls = ogw.getOWLClassByIdentifier(c);
			String gene = ann.getBioentity();

			// special case : protein binding
			if (c.equals("GO:0005515")) {
				for (String b : ann.getWithExpression().split("\\|")) {
					addPPI(gene, b);
				}
				continue;
			}

			for (List<ExtensionExpression> eel : ann.getExtensionExpressions()) {
				for (ExtensionExpression ee : eel) {
					// temporary measure - treat all ext expressions as PPIs
					addPPI(gene, ee.getCls());
				}
			}

			populationGeneSet.add(gene);
			String sym = ann.getBioentityObject().getSymbol();
			if (sym != null && !sym.equals(""))
				labelMap.put(gene, sym);


			if (!clsByGeneMap.containsKey(gene))
				clsByGeneMap.put(gene, new HashSet<OWLClass>());
			clsByGeneMap.get(gene).add(cls);

			for (OWLObject ancCls : g.getNamedAncestorsReflexive(cls)) {
				if (!(ancCls instanceof OWLClass)) {
					LOG.error(ancCls + " is ancestor of "+cls+" and not a class...?");
				}
				OWLClass anc = (OWLClass) ancCls;
				//LOG.info("   "+gene + " => "+c+" => "+anc + " // "+ancCls);
				if (!geneByInferredClsMap.containsKey(anc))
					geneByInferredClsMap.put(anc, new HashSet<String>());
				geneByInferredClsMap.get(anc).add(gene);				
			}
		}

		activityClassSet = new HashSet<OWLClass>();
		processClassSet = new HashSet<OWLClass>();
		for (OWLClass cls : g.getAllOWLClasses()) {
			String ns = g.getNamespace(cls);
			if (ns == null) ns = "";
			if (ns.equals("molecular_function")) {
				activityClassSet.add(cls);
			}
			else if (ns.equals("biological_process")) {
				processClassSet.add(cls);
			}
			else if (!ns.equals("cellular_component")) {
				LOG.info("Adding "+cls+" to process subset - I assume anything not a CC or MF is a process");
				// todo - make configurable. The default assumption is that phenotypes etc are treated as pathological process
				processClassSet.add(cls);
			}
			String label = g.getLabel(cls);
			if (label != "" && label != null)
				labelMap.put(cls, label);
		}

	}

	// adds an (external) protein-protein interaction
	private void addPPI(String a, String b) {
		if (!proteinInteractionMap.containsKey(a))
			proteinInteractionMap.put(a, new HashSet<String>());
		proteinInteractionMap.get(a).add(b);

	}

	/**
	 * @param id
	 * @return label for any class or entity in the graph
	 */
	public String getLabel(Object k) {
		if (k == null)
			return "Null";
		if (labelMap.containsKey(k))
			return this.labelMap.get(k);
		return k.toString();
	}

	public Map<String,Object> getGraphStatistics() {
		Map<String,Object> sm = new HashMap<String,Object>();
		sm.put("activity_node_count", activityNetwork.activitySet.size());
		sm.put("activity_edge_count", activityNetwork.activityEdgeSet.size());
		sm.put("process_count", processSet.size());
		return sm;
	}

	/**
	 * Translates ontological activation network into OWL (aka lego model)
	 * <ul>
	 *  <li> a = g x t &in; A &rarr; a &in; OWLNamedIndividual, a.iri = genIRI(g, t), a rdf:type t, a rdf:type (enabled_by some g)
	 *  <li> g &in; G &rarr; g &in; OWLClass, g SubClassOf Protein
	 *  <li> t &in; T &rarr; t &in; OWLClass 
	 *  <li> e = a1 x a2 x t &in; E &rarr; e &in; OWLObjectPropertyAssertion, e.subject = a1, e.object = a2, e.property = t
	 *  <li> p &in; P &rarr; p &in; OWLNamedIndividual
	 *  <li> m = p1 x p2 & &in; M &rarr; m &in; OWLObjectPropertyAssertion, m.subject = p1, m.object = p2, m.property = part_of
	 * <li>
	 * </ul>
	 * Notes: we treat all members of G as proteins, but these may be other kinds of gene product. Note also the source ID may be a gene ID.
	 * In this case we can substitute "enabled_by some g" with "enabled_by some (product_of some g)"
	 * 
	 * In some cases the edge type is not known - here we can use a generic owlTopProperty - or we can assume an activates relation, and leave the user to prune/modify
	 * 
	 * Warning: may possibly be refactored into a separate writer class.
	 * 
	 * @return
	 * @throws OWLOntologyCreationException 
	 */
	public OWLOntology translateNetworkToOWL() throws OWLOntologyCreationException {
		//if (exportOntology == null) {
		//IRI ontIRI = this.getIRI("TEMP:" + contextId);
		//LOG.info("ONT IRI = "+ontIRI);
		//exportOntology = getOWLOntologyManager().createOntology(ontIRI);
		//}
		// a = g x t &in; A &rarr; a &in; OWLNamedIndividual, a.iri = genIRI(g, t), a rdf:type t, a rdf:type (enabled_by some g)
		Map<Activity,String> activityToIdMap = new HashMap<Activity,String>();
		for (Activity a : activityNetwork.activitySet) {
			OWLClassExpression activityClass = a.activityClass;
			String gene = a.gene;
			if (activityClass == null)
				activityClass = ogw.getOWLClassByIdentifier("GO:0003674"); // TODO - use vocab
			if (gene == null)
				gene = "PR:00000001";
			//activityToIdMap.put(a, id);
			//addOwlInstanceRelationType(a.owlObject, "enabled_by", gene);
			//addOwlInstanceType(id, activityClass);
			String label = getLabel(a.owlObject) + " enabled by "+getLabel(gene);
			addOwlLabel(a.owlObject, label);
		}
		for (Edge<InstanceNode, InstanceNode> e : partonomy.edgeSet) {
			LOG.info("PTNMY="+e.subject + " --> "+e.object);
			// TODO - this is really contorted, all because we are overloading String in the partonomy
			/*
			Set<Activity> aset = activityNetwork.lookupByActivityType(e.subject);
			if (aset.size() > 0) {
				Activity a = aset.iterator().next();
				addOwlFact(activityToIdMap.get(a), e.type, e.object);
			}	
			else {
				addOwlFact(e.subject, e.type, e.object);
			}
			 */
			//addOwlLabel(e.subject, getLabel(e.subject));
			addOwlLabel(e.object.owlObject, getLabel(e.object)); // TODO <-- do this earlier
			//this.addOwlInstanceType(e.object, e.object); // PUNNING!!!
		}
		for (Edge<Activity, Activity> e : activityNetwork.activityEdgeSet) {

			addOwlFact(e.subject.owlObject,
					e.type,
					e.object.owlObject
					);
		}

		return exportOntology;
	}

	public OWLOntology translateNetworkToOWL(OWLOntology ont) throws OWLOntologyCreationException {
		exportOntology = ont;
		return translateNetworkToOWL();
	}


	private OWLOntologyManager getOWLOntologyManager() {
		if (owlOntologyManager == null)
			owlOntologyManager = OWLManager.createOWLOntologyManager();
		return owlOntologyManager;
	}


	private OWLDataFactory getOWLDataFactory() {
		return exportOntology.getOWLOntologyManager().getOWLDataFactory();
	}

	private void addAxiom(OWLAxiom ax) {
		exportOntology.getOWLOntologyManager().addAxiom(exportOntology, ax);
	}

	private IRI getIRI(String id) {
		return ogw.getIRIByIdentifier(id);
	}

	private OWLNamedIndividual getIndividual(String id) {
		return getOWLDataFactory().getOWLNamedIndividual(getIRI(id));
	}
	private OWLClass getOWLClass(String id) {
		return getOWLDataFactory().getOWLClass(getIRI(id));
	}

	private OWLObjectPropertyExpression getObjectProperty(String rel) {
		IRI iri;
		if (rel.equals("part_of"))
			rel = "BFO:0000050";
		if (rel.contains(":")) {
			iri = getIRI(rel);
		}
		else {
			iri = getIRI("http://purl.obolibrary.org/obo/"+rel); // TODO
		}
		return getOWLDataFactory().getOWLObjectProperty(iri);
	}

	@Deprecated
	private void addOwlFact(OWLIndividual subj, OWLObjectPropertyExpression type, OWLIndividual obj) {
		//LOG.info("Adding " + subj + "   "+rel + " "+obj);
		addAxiom(getOWLDataFactory().getOWLObjectPropertyAssertionAxiom(type, subj, obj));
	}

	private void addOwlData(OWLObject subj, OWLAnnotationProperty p, String val) {
		OWLLiteral lit = getOWLDataFactory().getOWLLiteral(val);
		addAxiom(getOWLDataFactory().getOWLAnnotationAssertionAxiom(
				p,
				((OWLNamedObject) subj).getIRI(), 
				lit));
	}

	private void addOwlLabel(OWLObject owlObject, String val) {
		addOwlData(owlObject, 
				getOWLDataFactory().getOWLAnnotationProperty(OWLRDFVocabulary.RDFS_LABEL.getIRI()),
				val);
	}


	@Deprecated
	private void addOwlInstanceType(String i, String t) {
		//LOG.info("Adding " + i + " instance of  "+t);
		addAxiom(getOWLDataFactory().getOWLClassAssertionAxiom(
				getOWLClass(t),
				getIndividual(i)));
	}

	@Deprecated
	private void addOwlInstanceRelationType(String i, String r, String t) {
		//LOG.info("Adding " + i + " instance of "+r+" some "+t);
		OWLClassExpression x = getOWLDataFactory().getOWLObjectSomeValuesFrom(
				getObjectProperty(r),
				getOWLClass(t));
		addAxiom(getOWLDataFactory().getOWLClassAssertionAxiom(
				x,
				getIndividual(i)));
	}



}
