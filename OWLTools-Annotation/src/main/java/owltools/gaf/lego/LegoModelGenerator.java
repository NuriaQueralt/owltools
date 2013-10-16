package owltools.gaf.lego;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.math.MathException;
import org.apache.commons.math.distribution.HypergeometricDistributionImpl;
import org.apache.log4j.Logger;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationProperty;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassAssertionAxiom;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLIndividual;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLNamedObject;
import org.semanticweb.owlapi.model.OWLObject;
import org.semanticweb.owlapi.model.OWLObjectPropertyAssertionAxiom;
import org.semanticweb.owlapi.model.OWLObjectPropertyExpression;
import org.semanticweb.owlapi.model.OWLObjectSomeValuesFrom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.OWLPropertyExpression;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.OWLReasonerFactory;
import org.semanticweb.owlapi.vocab.OWLRDFVocabulary;

import owltools.gaf.ExtensionExpression;
import owltools.gaf.GafDocument;
import owltools.gaf.GeneAnnotation;
import owltools.graph.OWLGraphEdge;
import owltools.graph.OWLGraphWrapper;
import owltools.graph.OWLQuantifiedProperty;
import owltools.util.MinimalModelGenerator;
import owltools.vocab.OBOUpperVocabulary;

/**
 * Generates a Functional Network (aka LEGO) graphß for a process given an ontology and a set of annotations.
 * A process can be a 'normal' biological process (e.g. from GO) or a pathological process
 * (we can treat MP as abnormal processes for the purposes of this analysis, or make a parallel ontology)
 * 
 * The input is a process class and a set of genes (e.g. all genes involved in that BP), construct the most likely
 * set of gene + activity-type (i.e. MF) pairs that are likely to be executed during this process.
 * Also infer causal relationships between gene-activity pairs based on PPI networks, annotation extensions.
 * 
 * The network generation is broken into two steps
 * 
 * <li><i>Minimal Model Generation</i>, which generates a minimal set of individuals inferred to exist given the existence of the process class.
 * <li>Model <i>Augmentation</I, in which statistical inferences are used to augment the above graph with best guesses about the roles performed
 * by gene products during the process
 *  
 *  <h2>Definitions</h2>
 *  
 *  <ul>
 *  <li> G : set of genes/products (for a whole genome/organism)
 *  <li> T : set of all ontology classes and object properties (OWL TBox and RBox)
 *  <li> T<sup>A</sup> : all ontology classes representing <i>atomic</i> processes, e.g. in MF (activity)
 *  <li> T<sup>P</sup> : all ontology classes representing <i>non-atomic</i> processes, e.g. in BP ontology
 *  <li> A : set of all activity instances in a functional network. Each activity is a gene / activity-class pair. ie A &sube; G x T<sup>A</sup>
 *  <li> E : Optionally typed connections between activity instances. ie E &sube; A x A x T<sup>Rel</sup>
 *  <li> P : Set of all process instances. P &sube; T<sup>P</sup>
 *  <li> M : Merelogy (partonomy), from activity instances to process instances, and between process instances.  ie M &sube; A &cup; P x P
 *  </ul>
 *  
 *  <h2>Algorithm - TODO - REDOCUMENT</h2>
 *  
 *  <h3>Inputs</h3>
 *  <ul>
 *  <li> {@link #seedGraph} - seed initial A
 *  <li> {@link #createPartonomy} - create partonomy P
 *  <li> {@link #connectGraph} - created activity network E
 *  </ul>
 *  
 *  <h2>Mapping to OWL</h2>
 *	 <ul>
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

 *  <h2>TODO</h2>
 *  
 *  <ul>
 *  <li>Convert to LEGO
 *  <li>Rules for inferring occurs_in (requires extending A to be A &sube; G x T<sup>A</sup> x T<sup>C</sup>)
 *  </ul>
 *  
 *
 */
public class LegoModelGenerator extends MinimalModelGenerator {


	private static Logger LOG = Logger.getLogger(LegoModelGenerator.class);

	/**
	 * E<sup>A</sup> &sube; A x A x T<sup>A</sup>
	 */
	//public ActivityNetwork activityNetwork;
	Map<String,Set<String>> proteinInteractionMap;
	Set<String> populationGeneSet;
	Map<Object,String> labelMap; 
	Map<OWLClass,Set<String>> geneByInferredClsMap;
	Map<OWLClass,Set<OWLClass>> anctesorMap;
	HashMap<String, Set<OWLClass>> clsByGeneMap;
	Set<OWLClass> activityClassSet;
	public Set<OWLClass> processClassSet;
	private Map<String,Set<OWLNamedIndividual>> activityByGene;
	Double ccp = null; // cumulative


	/**
	 * M &sube; N x N, N = A &cup; P
	 */
	//public Partonomy partonomy;
	//Set<Process> processSet; // P : process *instances*
	OWLGraphWrapper ogw;


	/**
	 * @param tbox
	 * @throws OWLOntologyCreationException
	 */
	public LegoModelGenerator(OWLOntology tbox) throws OWLOntologyCreationException {
		super(tbox);
	}

	/**
	 * @param tbox
	 * @param abox
	 * @param rf
	 * @throws OWLOntologyCreationException
	 */
	@Deprecated
	public LegoModelGenerator(OWLOntology tbox, OWLOntology abox, OWLReasonerFactory rf)
			throws OWLOntologyCreationException {
		super(tbox, abox, rf);
		// TODO Auto-generated constructor stub
	}


	/**
	 * @param tbox
	 * @param abox
	 * @throws OWLOntologyCreationException
	 */
	public LegoModelGenerator(OWLOntology tbox, OWLOntology abox)
			throws OWLOntologyCreationException {
		super(tbox, abox);
		// TODO Auto-generated constructor stub
	}


	/**
	 * @param tbox
	 * @param reasonerFactory
	 * @throws OWLOntologyCreationException
	 */
	public LegoModelGenerator(OWLOntology tbox, OWLReasonerFactory reasonerFactory)
			throws OWLOntologyCreationException {
		super(tbox, reasonerFactory);
		// TODO Auto-generated constructor stub
	}

	/**
	 * @param gafdoc
	 * @param g
	 */
	public void initialize(GafDocument gafdoc, OWLGraphWrapper g) {
		ogw = g;
		geneByInferredClsMap = new HashMap<OWLClass,Set<String>>();
		populationGeneSet = new HashSet<String>();
		clsByGeneMap = new HashMap<String,Set<OWLClass>>();
		labelMap = new HashMap<Object,String>();
		proteinInteractionMap = new HashMap<String,Set<String>>();
		activityByGene = new HashMap<String,Set<OWLNamedIndividual>>(); 

		setRemoveAmbiguousIndividuals(false);

		//this.setPrecomputePropertyClassCombinations(true);
		Set<OWLPropertyExpression> rels = 
				new HashSet<OWLPropertyExpression>(getInvolvedInRelations());

		for (GeneAnnotation ann : gafdoc.getGeneAnnotations()) {
			String c = ann.getCls();
			OWLClass cls = ogw.getOWLClassByIdentifier(c);
			String gene = ann.getBioentity();

			// special case : protein binding
			if (c.equals("GO:0005515")) {
				for (String b : ann.getWithExpression().split("\\|")) {
					//LOG.info("Adding PPI based on WITH col");
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

			//LOG.info("Finding ancestors of "+cls+" over "+rels);
			for (OWLObject ancCls : g.getAncestorsReflexive(cls, rels)) { // TODO-use reasoner
				if (ancCls instanceof OWLClass) {
					OWLClass anc = (OWLClass) ancCls;
					if (!isQueryClass(anc)) {
						//LOG.info("   "+gene + " => "+c+" => "+anc + " // "+ancCls);
						if (!geneByInferredClsMap.containsKey(anc))
							geneByInferredClsMap.put(anc, new HashSet<String>());
						geneByInferredClsMap.get(anc).add(gene);
					}
				}
			}
		}

		activityClassSet = getReasoner().getSubClasses(getOWLClass(OBOUpperVocabulary.GO_molecular_function), false).getFlattened();
		processClassSet = getReasoner().getSubClasses(getOWLClass(OBOUpperVocabulary.GO_biological_process), false).getFlattened();

		for (OWLClass cls : this.getTboxOntology().getClassesInSignature(true)) {
			String label = g.getLabel(cls);
			if (label != "" && label != null)
				labelMap.put(cls, label);
		}

	}


	/**
	 * Performs all steps to build activation network
	 * 
	 * @param processCls
	 * @param seedGenes
	 * @throws OWLOntologyCreationException 
	 */
	public void buildNetwork(OWLClass processCls, Collection<String> seedGenes) throws OWLOntologyCreationException {
		generateNecessaryIndividuals(processCls, true);
		addGenes(processCls, seedGenes);
		//inferLocations();
		connectGraph();
		normalizeDirections();
		combineMultipleEnablers();
	}


	public void buildNetwork(String processClsId, Collection<String> seedGenes) throws OWLOntologyCreationException {
		OWLClass processCls = ogw.getOWLClassByIdentifier(processClsId);
		buildNetwork(processCls, seedGenes);
	}

	public void addGenes(String processClsId, Collection<String> seedGenes) throws OWLOntologyCreationException {
		OWLClass processCls = ogw.getOWLClassByIdentifier(processClsId);
		addGenes(processCls, seedGenes);
	}


	/**
	 * @see seedGraph(String p, Set seed)
	 * @param processCls
	 * @throws OWLOntologyCreationException 
	 */
	public void addGenes(OWLClass processCls) throws OWLOntologyCreationException {
		addGenes(processCls, getGenes(processCls));
	}


	/**
	 * Create initial activation node set A for a process P and a set of seed genes
	 * 
	 * for all g &in; G<sup>seed</sup>, add a = <g,t> to A where f = argmax(p) { t :  t &in; T<sup>A</sup>, p=Prob( t | g) } 
	 * 
	 * @param processCls
	 * @param seedGenes
	 * @throws OWLOntologyCreationException 
	 * @throws MathException 
	 */
	public void addGenes(OWLClass processCls, Collection<String> seedGenes) throws OWLOntologyCreationException {

		LOG.info("Adding genes...");
		// TODO - use only instance-level parts and regulators

		Collection<OWLNamedIndividual> leafNodes = 
				new HashSet<OWLNamedIndividual>();


		Set<OWLObjectPropertyExpression> props = getInvolvedInRelations();
		for (OWLNamedIndividual i : getGeneratedIndividuals()) {
			boolean isOccurrent = false;
			for (OWLClass c : getReasoner().getTypes(i, false).getFlattened()) {
				if (c.getIRI().equals(OBOUpperVocabulary.GO_biological_process.getIRI())) {
					isOccurrent = true;
					break;
				}
				if (c.getIRI().equals(OBOUpperVocabulary.GO_molecular_function.getIRI())) {
					isOccurrent = true;
					break;
				}
			}
			if (!isOccurrent) {
				continue;
			}

			// note this has the potential to include individuals which p
			// is part of - need to contain the scope here. For now, go liberal
			//leafNodes.add(i);

			boolean isInValidPath = false;
			Set<OWLIndividual> path = getIndividualsInProperPath(i, props);
			path.add(i); // reflexive
			for (OWLIndividual m : path) {
				if (m instanceof OWLNamedIndividual && getPrototypeClass((OWLNamedIndividual) m) != null) {
					if (getPrototypeClass((OWLNamedIndividual) m).equals(processCls)) {
						isInValidPath = true;
						break;
					}
				}
			}
			LOG.info(" PPATH "+getIdLabelPair(i)+" "+path);
			if (isInValidPath) {
				LOG.info(" **VALID");
				leafNodes.add(i);
			}

		}


		//contextId = ogw.getIdentifier(processCls); // TODO
		ccp = 1.0; // cumulative
		//		generatedIndividuals = new HashSet<OWLNamedIndividual>();
		//		// note: here ancestors may be sub-parts
		//		for (OWLObject obj : ogw.getAncestorsReflexive(prototypeIndividualMap.get(processCls))) {
		//			LOG.info(" ANCI="+getIdLabelPair(obj));
		//			generatedIndividuals.add((OWLNamedIndividual) obj);
		//		}

		//activityNetwork = new ActivityNetwork();
		for (String g : seedGenes) {

			LOG.info("  Seed gene="+getIdLabelPair(g));

			// each gene, find it's most likely function and most direct process class.
			// each gene can have multiple functions.
			// in addition, a gene may be annotated to different parts of processCls
			//

			Set<OWLClass> geneActivityTypes = getMostSpecificActivityTypes(g);

			//Set<OWLClass> geneProcessTypes = getMostSpecificProcessTypes(g);
			Set<OWLClass> geneInferredTypes = getInferredTypes(g);

			LOG.info(" num inferred types = "+geneInferredTypes.size());


			// TODO - first find all other annotations for each gene
			// and add these if relevant.
			// E.g. in nodal pathway, Dand5 has 'sequestering of nodal from receptor via nodal binding'

			Collection<OWLNamedIndividual> joinPoints = 
					new HashSet<OWLNamedIndividual>();

			Set<OWLClass> otherProcesses = getMostSpecificProcessTypes(g);
			boolean isReset = false;
			for (OWLClass c : otherProcesses) {
				boolean isCandidate = false;
				for (OWLGraphEdge edge : ogw.getOutgoingEdgesClosure(c)) {
					if (edge.getTarget().equals(processCls)) {
						isCandidate = true;
						for (OWLObjectSomeValuesFrom r : getExistentialRelationships(c)) {
							if (getReasoner().getSuperClasses(r.getFiller(), false).getFlattened().contains(processCls)) {
								// should not cause a deepening
								isCandidate = false;
							}
						}
						// only use "pure" edges, no subclass, only involved in relations
						// TODO - fix me - 
						//						for (OWLQuantifiedProperty qp : edge.getQuantifiedPropertyList()) {
						//							if (!(qp.isSomeValuesFrom() && 
						//									getInvolvedInRelations().contains(qp.getProperty()))) {
						//								isCandidate = false;
						//								continue;
						//							}
						//						}
					}
				}
				if (isCandidate) {
					if (getReasoner().getSuperClasses(c, false).getFlattened().contains(processCls)) {
						// skip
						// todo - keep the information somehow that g is annotated to specific
						// subtypes of c
					}
					else {
						// experimenting with aggressive approach
						OWLNamedIndividual newp = generateNecessaryIndividuals(c);
						if (!isReset) {
							isReset = true;
							joinPoints = new HashSet<OWLNamedIndividual>();
							//generatedIndividuals = new HashSet<OWLNamedIndividual>();
						}
						LOG.info(" Using more specific annotation: "+getIdLabelPair(c));
						joinPoints.add(newp);
					}
				}
			}
			if (joinPoints.size() == 0) {
				joinPoints.addAll(leafNodes);
			}


			Double best = null;
			Double cp = 1.0;
			OWLClass bestActivityClass = null;
			OWLClass bestParentClass = null;
			OWLNamedIndividual bestParent = null;
			for (OWLNamedIndividual joinPoint : joinPoints) {
				LOG.info(" candidate join point="+getIdLabelPair(joinPoint));
				//OWLClass joinPointClass = getPrototypeClass(joinPoint);

				//this seemed to be producing redundant types:
				Set<OWLClass> jpcs = getReasoner().getTypes(joinPoint, true).getFlattened();
				Set<OWLClass> rd = new HashSet<OWLClass>();
				for (OWLClass jpc : jpcs) {
					rd.addAll(getReasoner().getSuperClasses(jpc, false).getFlattened());
				}
				jpcs.removeAll(rd);
				
				for (OWLClass joinPointClass : jpcs) {
					
					// a gene must have been annotated to some descendant of generatedCls to be considered.
					if (geneInferredTypes.contains(joinPointClass)) {
						for (OWLClass activityCls : geneActivityTypes ) {
							// note that generatedCls may be a MF, and may be a subclass,
							// which case this would be 1.0
							Double pval;
							try {
								pval = calculatePairwiseEnrichment(activityCls, joinPointClass);
								cp = this.calculateConditionalProbaility(joinPointClass, activityCls);
								LOG.info("enrichment of "+getIdLabelPair(activityCls)+" IN: "+getIdLabelPair(joinPointClass)+
										" = "+pval);
								// temp hack - e.g. frp1 ferric-chelate reductase in iron assimilation by reduction and transport
								if (activityCls.equals(joinPointClass)) {
									pval = 0.0;
								}

								if (best == null || pval < best) {
									// 
									// TODO - pval == best
									best = pval;
									bestActivityClass = activityCls;
									bestParentClass = joinPointClass;
									bestParent = joinPoint;
								}
							} catch (MathException e) {
								// TODO Auto-generated catch block
								e.printStackTrace();
							}
						}
					}
					else {
						LOG.info("Skpping: "+getIdLabelPair(joinPointClass)+" -- not in geneInferredTypes");
					}
				}
				//LOG.info(" DONE testIndivid="+getIdLabelPair(gi));
			}

			OWLNamedIndividual ai = addActivity(bestActivityClass, g, best, cp);
			ccp *= cp;
			OWLObjectPropertyExpression relation = getObjectProperty(OBOUpperVocabulary.BFO_part_of);
			LOG.info("Testing if "+getIdLabelPair(bestActivityClass) + " is under "+getIdLabelPair(bestParentClass));
			if (bestActivityClass != null) {
				if (getReasoner().getSubClasses(bestActivityClass, false).getFlattened().contains(bestParentClass) ||
						getReasoner().getEquivalentClasses(bestActivityClass).getEntities().contains(bestParentClass)) {
					LOG.info("Merging "+bestParent+" --> "+ai);
					mergeInto(bestParent, ai);
				}
				else {
					addEdge(ai, relation, bestParent);
				}
			}
		}
		collapseIndividuals();
	}



	private void addEdge(OWLNamedIndividual p, OWLObjectPropertyExpression rel, OWLNamedIndividual w) {
		if (ogw.getAncestorsReflexive(p).contains(w)) {
			LOG.info("ALREADY CONNECTED TO "+w);
			return;
		}
		if (w != null) {

			OWLAxiom owlObject = 
					getOWLDataFactory().getOWLObjectPropertyAssertionAxiom(
							rel,
							p,
							w);
			addAxiom(owlObject);
		}
		else {
			LOG.warn("Parent of "+p+" is null");
		}
	}

	private OWLNamedIndividual addActivity(OWLClass bestActivityClass, String gene, Double pval, Double cp) {
		if (bestActivityClass == null) {
			bestActivityClass =  getOWLDataFactory().getOWLClass(OBOUpperVocabulary.GO_molecular_function.getIRI());
		}
		OWLNamedIndividual ai = this.generateNecessaryIndividuals(bestActivityClass);
		//this.collapseIndividuals();

		String label = getLabel(bestActivityClass);
		OWLClass geneProductClass = null;
		if (gene != null) {
			geneProductClass = getOWLClassByIdentifier(gene);
		}
		else {
			//geneProductClass = getOWLClass("PR:00000001");
		}
		if (geneProductClass != null) {
			OWLClassExpression x = getOWLDataFactory().getOWLObjectSomeValuesFrom(
					getObjectProperty(OBOUpperVocabulary.GOREL_enabled_by),
					geneProductClass); // TODO <-- protein IRI should be here
			addAxiom(getOWLDataFactory().getOWLClassAssertionAxiom(
					x,
					ai));
			String geneLabel = getLabel(gene);
			addOwlLabel(geneProductClass, geneLabel);
			label = label + " enabled by " + geneLabel;
			if (!activityByGene.containsKey(gene)) {
				activityByGene.put(gene, new HashSet<OWLNamedIndividual>());
			}
			activityByGene.get(gene).add(ai);
		}
		label = label + " (pVal_uncorrected="+pval+", p(F|P)="+cp+")";
		addOwlLabel(ai, label);

		return ai;
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
			Set<OWLNamedIndividual> aset = lookupActivityByGene(p1);
			if (aset == null || aset.size() == 0)
				continue;
			//LOG.info("P1="+getIdLabelPair(p1));
			for (String p2 : proteinInteractionMap.get(p1)) {
				//LOG.info(" P2="+getIdLabelPair(p2));
				Set<OWLNamedIndividual> aset2 = lookupActivityByGene(p2);
				if (aset2 != null) {
					// assumption: if P1 and P2 interact then their activities also interact
					for (OWLNamedIndividual a1 : aset) {
						for (OWLNamedIndividual a2 : aset2) {
							LOG.info(" PPI-based edge: "+a1+" => "+a2);
							addEdge(a1, getObjectProperty(OBOUpperVocabulary.GOREL_provides_input_for), a2); // TODO
						}
					}
				}
			}
		}

		// Using ontology knowledge (e.g. connected_to relationships; has_input = has_output)

		//for (OWLNamedIndividual a : activityNetwork.activitySet) {
		//
		//}

		// Annotation extension method
		// TODO: e.g. PomBase SPCC645.07      rgf1            GO:0032319-regulation of Rho GTPase activity    PMID:16324155   IGI     PomBase:SPAC1F7.04      P       RhoGEF for Rho1, Rgf1           protein taxon:4896      20100429  PomBase 
		// in: =GO:0051666 ! actin cortical patch localization
	}

	private Set<OWLNamedIndividual> lookupActivityByGene(String g) {
		if (activityByGene.containsKey(g))
			return activityByGene.get(g);
		else
			return null;

	}

	private void combineMultipleEnablers() {

		OWLObjectPropertyExpression enabledBy = this.getObjectProperty(OBOUpperVocabulary.GOREL_enabled_by);
		for (OWLNamedIndividual i : getAboxOntology().getIndividualsInSignature(false)) {
			Set<OWLAxiom> rmAxioms = new HashSet<OWLAxiom>();
			Set<OWLClassExpression> xs = new HashSet<OWLClassExpression>();
			for (OWLAxiom ax : getAboxOntology().getAxioms(i)) {

				if (ax instanceof OWLClassAssertionAxiom) {
					//LOG.info("TESTING:"+ax);
					OWLClassAssertionAxiom caa = (OWLClassAssertionAxiom)ax;
					if (caa.getClassExpression().getObjectPropertiesInSignature().contains(enabledBy)) {
						rmAxioms.add(ax);
						if (caa.getClassExpression() instanceof OWLObjectSomeValuesFrom) {
							xs.add(((OWLObjectSomeValuesFrom)caa.getClassExpression()).getFiller());
						}
						else {
							LOG.warn("Hmmmm"+caa);
							//xs.add(caa.getClassExpression());
						}
					}
				}
			}
			if (xs.size() > 1) {
				LOG.info("Concatenating enables for "+getIdLabelPair(i));
				getOWLOntologyManager().removeAxioms(this.getAboxOntology(), rmAxioms);
				addAxiom(
						getOWLDataFactory().getOWLClassAssertionAxiom(
								getOWLDataFactory().getOWLObjectSomeValuesFrom(
										enabledBy,
										getOWLDataFactory().getOWLObjectUnionOf(xs)
										),
										i));
			}
		}
	}



	/**
	 * Reroute all has_parts to part_ofs
	 * 
	 */
	public void normalizeDirections() {
		normalizeDirections(getObjectProperty(OBOUpperVocabulary.BFO_part_of));
		normalizeDirections(getObjectProperty(OBOUpperVocabulary.BFO_occurs_in));
		normalizeDirections(getObjectProperty(OBOUpperVocabulary.RO_starts));
		normalizeDirections(getObjectProperty(OBOUpperVocabulary.RO_ends));
	}



	///////////////////
	//
	// Calculation of gene-class relationships and probabilities

	public Double calculatePairwiseEnrichment(OWLClass sampleSetClass, OWLClass enrichedClass) throws MathException {
		return calculatePairwiseEnrichment(sampleSetClass, enrichedClass, populationGeneSet.size());
	}

	public Double calculatePairwiseEnrichment(
			OWLClass sampleSetClass, OWLClass enrichedClass, int populationClassSize) throws MathException {

		// LOG.info("Hyper :"+populationClass
		// +" "+sampleSetClass+" "+enrichedClass);
		int sampleSetClassSize = getGenes(sampleSetClass).size();
		int enrichedClassSize = getGenes(enrichedClass).size();
		// LOG.info("Hyper :"+populationClassSize
		// +" "+sampleSetClassSize+" "+enrichedClassSize);
		HypergeometricDistributionImpl hg = new HypergeometricDistributionImpl(
				populationClassSize, sampleSetClassSize, enrichedClassSize);
		/*
		 * LOG.info("popsize="+getNumElementsForAttribute(populationClass));
		 * LOG.info("sampleSetSize="+getNumElementsForAttribute(sampleSetClass));
		 * LOG.info("enrichedClass="+getNumElementsForAttribute(enrichedClass));
		 */
		Set<String> eiSet = getGenes(sampleSetClass);
		eiSet.retainAll(getGenes(enrichedClass));
		// LOG.info("both="+eiSet.size());
		double p = hg.cumulativeProbability(eiSet.size(),
				Math.min(sampleSetClassSize, enrichedClassSize));
		//double pCorrected = p * getCorrectionFactor(populationClass);
		return p;
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
		removeRedundantOrHelper(cset, null);
		return cset;
	}

	public Set<OWLClass>  getMostSpecificProcessTypes(String g) {
		Set<OWLClass> cset = getProcessTypes(g);
		removeRedundantOrHelper(cset, null);
		return cset;
	}

	public Set<OWLClass> getProcessTypes(String g) {
		HashSet<OWLClass> cset = new HashSet<OWLClass>(clsByGeneMap.get(g));
		cset.retainAll(processClassSet);
		return cset;
	}

	public Set<OWLClass> getInferredTypes(String g) {
		HashSet<OWLClass> cset = new HashSet<OWLClass>();
		Set<OWLPropertyExpression> rels = 
				new HashSet<OWLPropertyExpression>(getInvolvedInRelations());
		for (OWLClass c : clsByGeneMap.get(g)) {
			for (OWLObject a : ogw.getAncestorsReflexive(c, rels)) { // TODO-rel
				if (a instanceof OWLClass && !isQueryClass((OWLClass) a)) {
					cset.add((OWLClass) a);
				}
			}
		}
		return cset;
	}

	public Set<OWLObjectSomeValuesFrom> getInferredRelationshipsForGene(String g) {
		Set<OWLObjectSomeValuesFrom> results = new HashSet<OWLObjectSomeValuesFrom>();
		HashSet<OWLClass> cset = new HashSet<OWLClass>(clsByGeneMap.get(g));
		for (OWLClass c : cset) {
			results.addAll(getExistentialRelationships(c));
		}
		return results;
	}

	private void removeRedundantOrHelper(Set<OWLClass> cset, Set<OWLPropertyExpression> props) {
		Set<OWLClass> allAncs = new HashSet<OWLClass>();
		for (OWLClass c : cset) {
			if (isQueryClass(c)) {
				allAncs.add(c);
				continue;
			}
			Set<OWLObject> ancClsSet = ogw.getAncestors(c, props);
			for (OWLObject obj : ancClsSet) {
				if (obj instanceof OWLClass) {
					// named ancestors only
					allAncs.add((OWLClass) obj);
				}

			}
		}
		cset.removeAll(allAncs);
	}

	/**
	 * Gets all genes annotated to cls or descendant
	 * @param t
	 * @return { g : g x t &in; InferredInvolvedIn }
	 */
	public Set<String> getGenes(OWLClass wholeCls) {
		if (!geneByInferredClsMap.containsKey(wholeCls)) {
			//LOG.info("Nothing known about "+wholeCls);
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

	public String getIdLabelPair(Object k) {
		if (k == null)
			return "Null";
		if (labelMap.containsKey(k))
			return k+" '"+this.labelMap.get(k)+"'";
		return k.toString();
	}


	public Map<String,Object> getGraphStatistics() {
		Map<String,Object> sm = new HashMap<String,Object>();
		//sm.put("activity_node_count", activityNetwork.activitySet.size());
		//sm.put("activity_edge_count", activityNetwork.activityEdgeSet.size());
		//sm.put("process_count", processSet.size());
		return sm;
	}



	private IRI getIRI(String id) {
		return ogw.getIRIByIdentifier(id);
	}

	private OWLNamedIndividual getIndividual(String id) {
		return getOWLDataFactory().getOWLNamedIndividual(getIRI(id));
	}



	private OWLClass getOWLClassByIdentifier(String id) {
		return getOWLDataFactory().getOWLClass(ogw.getIRIByIdentifier(id));
	}
	private OWLClass getOWLClass(OBOUpperVocabulary v) {
		return getOWLDataFactory().getOWLClass(v.getIRI());
	}


	private Set<OWLObjectPropertyExpression> getInvolvedInRelations() {
		Set<OWLObjectPropertyExpression> rels = new HashSet<OWLObjectPropertyExpression>();
		rels.add(getObjectProperty(OBOUpperVocabulary.BFO_part_of));
		rels.add(getObjectProperty(OBOUpperVocabulary.RO_regulates));

		// these should be inferred in the future:
		rels.add(getObjectProperty(OBOUpperVocabulary.RO_negatively_regulates));
		rels.add(getObjectProperty(OBOUpperVocabulary.RO_positively_regulates));
		rels.add(getObjectProperty(OBOUpperVocabulary.RO_starts)); // sub of part_of
		//rels.add(getObjectProperty(OBORelationsVocabulary.BFO_occurs_in));
		return rels;
	}


	private OWLObjectPropertyExpression getObjectProperty(
			OBOUpperVocabulary vocab) {
		// TODO Auto-generated method stub
		return getObjectProperty(vocab.getIRI());
	}
	private OWLObjectPropertyExpression getObjectProperty(
			IRI iri) {
		// TODO Auto-generated method stub
		return getOWLDataFactory().getOWLObjectProperty(iri);
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
		labelMap.put(owlObject, val);
	}




}
