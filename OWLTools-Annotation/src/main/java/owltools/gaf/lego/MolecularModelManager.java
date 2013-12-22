package owltools.gaf.lego;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.log4j.Logger;
import org.coode.owlapi.manchesterowlsyntax.ManchesterOWLSyntaxOntologyFormat;
import org.geneontology.lego.dot.LegoDotWriter;
import org.geneontology.lego.dot.LegoRenderer;
import org.geneontology.lego.model.LegoTools.UnExpectedStructureException;
import org.semanticweb.elk.owlapi.ElkReasonerFactory;
import org.semanticweb.owlapi.model.AddAxiom;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLAxiomChange;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassAssertionAxiom;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLIndividual;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLObjectPropertyAssertionAxiom;
import org.semanticweb.owlapi.model.OWLObjectPropertyExpression;
import org.semanticweb.owlapi.model.OWLObjectSomeValuesFrom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyFormat;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.OWLOntologyStorageException;
import org.semanticweb.owlapi.model.RemoveAxiom;
import org.semanticweb.owlapi.reasoner.OWLReasoner;

import owltools.gaf.GafDocument;
import owltools.gaf.GafObjectsBuilder;
import owltools.graph.OWLGraphWrapper;
import owltools.vocab.OBOUpperVocabulary;

/**
 * Manager object for operations on collections of MolecularModels (aka lego diagrams)
 * 
 * any number of models can be loaded at any time (todo - impose some limit to avoid
 * using too much memory)
 * 
 * each model has a generator, an OWLOntology (containing the set of class assertions)
 * and a reasoner associated with it (todo - test memory requirements)
 * 
 * This manager is designed to be used within a web server. Multiple clients can
 * contact the same manager instance through services
 *
 */
public class MolecularModelManager {
	
	private static Logger LOG = Logger.getLogger(MolecularModelManager.class);


	LegoModelGenerator molecularModelGenerator;
	OWLGraphWrapper graph;
	boolean isPrecomputePropertyClassCombinations;
	Map<String, GafDocument> dbToGafdoc = new HashMap<String, GafDocument>();
	Map<String, LegoModelGenerator> modelMap = new HashMap<String, LegoModelGenerator>();
	String pathToGafs = "gene-associations";
	String pathToOWLFiles = "owl-models";
	GafObjectsBuilder builder = new GafObjectsBuilder();
	OWLOntologyFormat ontologyFormat = new ManchesterOWLSyntaxOntologyFormat();


	/**
	 * Represents the reponse to a requested translation on an
	 * ontology/model
	 * 
	 */
	public class OWLOperationResponse {
		OWLAxiomChange change;
		int changeId;
		boolean isSuccess = true;
		boolean isResultsInInconsistency = false;
		
		/**
		 * @param isSuccess
		 */
		public OWLOperationResponse(boolean isSuccess) {
			super();
			this.isSuccess = isSuccess;
		}
		/**
		 * @param isSuccess
		 * @param isResultsInInconsistency
		 */
		public OWLOperationResponse(boolean isSuccess,
				boolean isResultsInInconsistency) {
			super();
			this.isSuccess = isSuccess;
			this.isResultsInInconsistency = isResultsInInconsistency;
		}
		
		public OWLOperationResponse(OWLAxiomChange change, boolean isSuccess,
				boolean isResultsInInconsistency) {
			super();
			this.isSuccess = isSuccess;
			this.isResultsInInconsistency = isResultsInInconsistency;
			this.change = change;
		}
		
		

	}

	/**
	 * @param graph
	 * @throws OWLOntologyCreationException
	 */
	public MolecularModelManager(OWLGraphWrapper graph) throws OWLOntologyCreationException {
		super();
		this.graph = graph;
		init();
	}
	/**
	 * @param ont
	 * @throws OWLOntologyCreationException
	 */
	public MolecularModelManager(OWLOntology ont) throws OWLOntologyCreationException {
		super();
		this.graph = new OWLGraphWrapper(ont);
		init();
	}

	protected void init() throws OWLOntologyCreationException {
		molecularModelGenerator = 
				new LegoModelGenerator(graph.getSourceOntology(), new ElkReasonerFactory());
	}


	/**
	 * @return graph wrapper for core/source ontology
	 */
	public OWLGraphWrapper getGraph() {
		return graph;
	}

	/**
	 * @return core/source ontology
	 */
	public OWLOntology getOntology() {
		return graph.getSourceOntology();
	}


	/**
	 * @return path to gafs direcory
	 */
	public String getPathToGafs() {
		return pathToGafs;
	}
	/**
	 * Can either be an HTTP prefix, or an absolute file path
	 * 
	 * @param pathToGafs
	 */
	public void setPathToGafs(String pathToGafs) {
		this.pathToGafs = pathToGafs;
	}
	
	
	
	/**
	 * Note this may move to an implementation-specific subclass in future
	 * 
	 * @return path to owl on server
	 */
	public String getPathToOWLFiles() {
		return pathToOWLFiles;
	}
	/**
	 * @param pathToOWLFiles
	 */
	public void setPathToOWLFiles(String pathToOWLFiles) {
		this.pathToOWLFiles = pathToOWLFiles;
	}
	/**
	 * loads/register a Gaf document
	 * 
	 * @param db
	 * @return Gaf document
	 * @throws IOException
	 * @throws URISyntaxException
	 */
	public GafDocument loadGaf(String db) throws IOException, URISyntaxException {
		if (!dbToGafdoc.containsKey(db)) {

			GafDocument gafdoc = builder.buildDocument(pathToGafs + "/" + db + ".gz");
			dbToGafdoc.put(db, gafdoc);
		}
		return dbToGafdoc.get(db);
	}

	/**
	 * Loads and caches a GAF document from a specified location
	 * 
	 * @param db
	 * @param gafFile
	 * @return Gaf document
	 * @throws IOException
	 * @throws URISyntaxException
	 */
	public GafDocument loadGaf(String db, File gafFile) throws IOException, URISyntaxException {
		if (!dbToGafdoc.containsKey(db)) {

			GafDocument gafdoc = builder.buildDocument(gafFile);
			dbToGafdoc.put(db, gafdoc);
		}
		return dbToGafdoc.get(db);
	}


	/**
	 * @param db
	 * @return Gaf document for db
	 * @throws IOException
	 * @throws URISyntaxException
	 */
	public GafDocument getGaf(String db) throws IOException, URISyntaxException {
		return loadGaf(db);
	}


	/**
	 * Generates a 
	 * 
	 * See {@link LegoModelGenerator#buildNetwork(OWLClass, java.util.Collection)}
	 * 
	 * @param processCls
	 * @param db
	 * @return modelId
	 * @throws OWLOntologyCreationException
	 * @throws URISyntaxException 
	 * @throws IOException 
	 */
	public String generateModel(OWLClass processCls, String db) throws OWLOntologyCreationException, IOException, URISyntaxException {

		molecularModelGenerator.setPrecomputePropertyClassCombinations(isPrecomputePropertyClassCombinations);
		GafDocument gafdoc = getGaf(db);
		molecularModelGenerator.initialize(gafdoc, graph);

		Set<String> seedGenes = new HashSet<String>();
		String p = graph.getIdentifier(processCls);
		seedGenes.addAll(molecularModelGenerator.getGenes(processCls));

		molecularModelGenerator.buildNetwork(p, seedGenes);

		//OWLOntology model = molecularModelGenerator.getAboxOntology();
		String modelId = getModelId(p, db);
		modelMap.put(modelId, molecularModelGenerator);
		return modelId;

	}

	/**
	 * Adds a process individual (and inferred individuals) to a model
	 * 
	 * @param modelId
	 * @param processCls
	 * @return null TODO
	 * @throws OWLOntologyCreationException
	 */
	public String addProcess(String modelId, OWLClass processCls) throws OWLOntologyCreationException {
		LegoModelGenerator mod = getModel(modelId);
		Set<String> genes = new HashSet<String>();
		mod.buildNetwork(processCls, genes);
		return null;
	}

	/**
	 * 
	 * @param modelId
	 * @return all individuals in the model
	 * @throws OWLOntologyCreationException 
	 */
	public Set<OWLNamedIndividual> getIndividuals(String modelId) {
		LegoModelGenerator mod = getModel(modelId);
		return mod.getAboxOntology().getIndividualsInSignature();
	}

	/**
	 * @param modelId
	 * @return List of key-val pairs ready for Gson
	 */
	public List<Map> getIndividualObjects(String modelId) {
		LegoModelGenerator mod = getModel(modelId);
		MolecularModelJsonRenderer renderer = new MolecularModelJsonRenderer(graph);
		OWLOntology ont = mod.getAboxOntology();
		List<Map> objs = new ArrayList();
		for (OWLNamedIndividual i : ont.getIndividualsInSignature()) {
			objs.add(renderer.renderObject(ont, i));
		}
		return objs;
	}
	
	/**
	 * @param modelId
	 * @return Map object ready for Gson
	 */
	public Map<String,Object> getModelObject(String modelId) {
		LegoModelGenerator mod = getModel(modelId);
		MolecularModelJsonRenderer renderer = new MolecularModelJsonRenderer(graph);
		return renderer.renderObject(mod.getAboxOntology());
	}

	/**
	 * Given an instance, generate the most specific class instance that classifies
	 * this instance, and add this as a class to the model ontology
	 * 
	 * @param modelId
	 * @param individualId
	 * @param newClassId
	 * @return newClassId
	 */
	public String createMostSpecificClass(String modelId, String individualId, String newClassId) {
		LegoModelGenerator mod = getModel(modelId);
		OWLIndividual ind = getIndividual(modelId, individualId);
		OWLClassExpression msce = mod.getMostSpecificClassExpression((OWLNamedIndividual) ind);
		OWLClass c = this.getClass(newClassId);
		addAxiom(modelId, getOWLDataFactory(modelId).getOWLEquivalentClassesAxiom(msce, c));
		return newClassId;
	}

	/**
	 * TODO - autogenerate a label?
	 * TODO - finalize identifier policy. Currently concatenates model and class IDs
	 * 
	 * @param modelId
	 * @param c
	 * @return id of created individual
	 */
	public String createIndividual(String modelId, OWLClass c) {
		LOG.info("Creating individual of type: "+c);
		String cid = graph.getIdentifier(c).replaceAll(":","-"); // e.g. GO-0123456
		String iid = modelId+"-"+cid; // TODO - uniqueify
		LOG.info("  new OD: "+iid);
		IRI iri = graph.getIRIByIdentifier(iid);
		OWLNamedIndividual i = getOWLDataFactory(modelId).getOWLNamedIndividual(iri);
		addAxiom(modelId, getOWLDataFactory(modelId).getOWLDeclarationAxiom(i));
		addType(modelId, i, c);
		return iid;
	}

	/**
	 * Shortcut for {@link #createIndividual(String, OWLClass)}
	 * 
	 * @param modelId
	 * @param cid
	 * @return id of created individual
	 */
	public String createIndividual(String modelId, String cid) {
		return createIndividual(modelId, getClass(cid));
	}
	
	/**
	 * Deletes an individual
	 * 
	 * @param modelId
	 * @param iid
	 * @return response into
	 */
	public OWLOperationResponse deleteIndividual(String modelId,String iid) {
		OWLNamedIndividual i = (OWLNamedIndividual) getIndividual(modelId, iid);
		removeAxiom(modelId, getOWLDataFactory(modelId).getOWLDeclarationAxiom(i));
		LegoModelGenerator m = getModel(modelId);
		OWLOntology ont = m.getAboxOntology();
		for (OWLAxiom ax : ont.getAxioms(i)) {
			removeAxiom(modelId, ax);
		}
		return new OWLOperationResponse(true);
	}

	/**
	 * @param modelId
	 * @param c
	 * @return id of created individual
	 */
	public String createActivityIndividual(String modelId, OWLClass c) {
		return createIndividual(modelId, c);
	}

	/**
	 * @param modelId
	 * @param c
	 * @return id of created individual
	 */
	public String createProcessIndividual(String modelId, OWLClass c) {
		return createIndividual(modelId, c);
	}

	/**
	 * Fetches a model by its Id
	 * 
	 * @param id
	 * @return wrapped model
	 * @throws OWLOntologyCreationException 
	 */
	public LegoModelGenerator getModel(String id)  {
		if (!modelMap.containsKey(id)) {
			try {
				loadModel(id, false);
			} catch (OWLOntologyCreationException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		return modelMap.get(id);
	}
	/**
	 * @param id
	 */
	public void unlinkModel(String id) {
		LegoModelGenerator model = modelMap.get(id);
		model.dispose();
		modelMap.remove(id);
	}
	/**
	 * @param id
	 */
	public void deleteModel(String id) {
		// TODO - retrieve from persistent store
		modelMap.remove(id);
	}
	
	/**
	 * @return ids for all loaded models
	 */
	public Set<String> getModelIds() {
		return modelMap.keySet();
	}

	/**
	 * TODO - locking
	 * 
	 * @param modelId 
	 * @throws OWLOntologyStorageException 
	 * @throws OWLOntologyCreationException 
	 */
	public void saveModel(String modelId) throws OWLOntologyStorageException, OWLOntologyCreationException {
		// TODO - delegate to a bridge object, allow different impls (triplestore, filesystem, etc)
		LegoModelGenerator m = getModel(modelId);
		OWLOntology ont = m.getAboxOntology();
		String file = getPathToModelOWL(modelId);
		getOWLOntologyManager(modelId).saveOntology(ont, ontologyFormat, IRI.create(new File(file)));
	}
	
	/**
	 * @throws OWLOntologyStorageException
	 * @throws OWLOntologyCreationException
	 */
	public void saveAllModels() throws OWLOntologyStorageException, OWLOntologyCreationException {
		for (String modelId : modelMap.keySet()) {
			saveModel(modelId);
		}
	}
	
	// TODO - ensure load/save are synchronized
	protected void loadModel(String modelId, boolean isOverride) throws OWLOntologyCreationException {
		if (modelMap.containsKey(modelId)) {
			if (!isOverride) {
				throw new OWLOntologyCreationException("Model already esxists: "+modelId);
			}
		}
		String file = getPathToModelOWL(modelId);
		
		OWLOntology ont = graph.getManager().loadOntologyFromOntologyDocument(IRI.create(new File(file)));
		LegoModelGenerator m = new LegoModelGenerator(graph.getSourceOntology());
		m.setAboxOntology(ont);
		modelMap.put(modelId, m);
	}

	
	private String getPathToModelOWL(String modelId) {
		return pathToOWLFiles + "/" + modelId + ".owl";
	}

	private OWLIndividual getIndividual(String modelId, String iid) {
		IRI iri = graph.getIRIByIdentifier(iid);
		return getOWLDataFactory(modelId).getOWLNamedIndividual(iri);
	}
	private OWLClass getClass(String cid) {
		IRI iri = graph.getIRIByIdentifier(cid);
		return graph.getDataFactory().getOWLClass(iri);
	}
	private OWLObjectProperty getObjectProperty(String pid) {
		IRI iri = graph.getIRIByIdentifier(pid);
		return graph.getDataFactory().getOWLObjectProperty(iri);
	}

	private OWLObjectPropertyExpression getObjectProperty(
			OBOUpperVocabulary vocabElement) {
		return vocabElement.getObjectProperty(getOntology());
	}

	/**
	 * 
	 * @param modelId
	 * @return true if the ontology formed by the specified model is inconsistent
	 */
	public boolean isConsistent(String modelId) {
		LegoModelGenerator model = getModel(modelId);
		// TODO - is it scalable to have each model have its own reasoner?
		// may make more sense to have a single reasoner instance operating over entire kb;
		// this would mean the entire kb should be kept consistent - an inconsistency in one
		// model would mean the entire kb is inconsistent
		return model.getReasoner().isConsistent();
	}

	/**
	 * @param modelId
	 * @return data factory for the specified model
	 * @throws OWLOntologyCreationException 
	 */
	public OWLDataFactory getOWLDataFactory(String modelId) {
		LegoModelGenerator model = getModel(modelId);
		return model.getOWLDataFactory();
	}

	protected OWLOntologyManager getOWLOntologyManager(String modelId) {
		LegoModelGenerator model = getModel(modelId);
		return model.getAboxOntology().getOWLOntologyManager();
	}

	/**
	 * Adds ClassAssertion(c,i) to specified model
	 * 
	 * @param modelId
	 * @param i
	 * @param c
	 * @return response info
	 */
	public OWLOperationResponse addType(String modelId,
			OWLIndividual i, OWLClass c) {
		OWLClassAssertionAxiom axiom = getOWLDataFactory(modelId).getOWLClassAssertionAxiom(c,i);
		return addAxiom(modelId, axiom);
	}

	/**
	 * Convenience wrapper for {@link #addType(String, OWLIndividual, OWLClass)}
	 * 
	 * @param modelId
	 * @param iid
	 * @param cid
	 * @return response info
	 */
	public OWLOperationResponse addType(String modelId,
			String iid, String cid) {
		return addType(modelId, getIndividual(modelId, iid), getClass(cid));
	}




	/**
	 * Adds a ClassAssertion, where the class expression instantiated is an
	 * ObjectSomeValuesFrom expression
	 * 
	 * Example: Individual: i Type: enabledBy some PRO_123 
	 * 
	 * @param modelId
	 * @param i
	 * @param p
	 * @param filler
	 * @return response info
	 */
	public OWLOperationResponse addType(String modelId,
			OWLIndividual i, 
			OWLObjectPropertyExpression p,
			OWLClassExpression filler) {
		LOG.info("Adding "+i+ " type "+p+" some "+filler);
		OWLObjectSomeValuesFrom c = getOWLDataFactory(modelId).getOWLObjectSomeValuesFrom(p, filler);
		OWLClassAssertionAxiom axiom = 
				getOWLDataFactory(modelId).getOWLClassAssertionAxiom(
						c,
						i);
		return addAxiom(modelId, axiom);
	}
	
	/**
	 * Adds ClassAssertion(c,i) to specified model
	 * 
	 * @param modelId
	 * @param i
	 * @param c
	 * @return response info
	 */
	public OWLOperationResponse removeType(String modelId,
			OWLIndividual i, OWLClass c) {
		OWLClassAssertionAxiom axiom = getOWLDataFactory(modelId).getOWLClassAssertionAxiom(c,i);
		return addAxiom(modelId, axiom);
	}

	/**
	 * Convenience wrapper for {@link #removeType(String, OWLIndividual, OWLClass)}
	 * 
	 * @param modelId
	 * @param iid
	 * @param cid
	 * @return response info
	 */
	public OWLOperationResponse removeType(String modelId,
			String iid, String cid) {
		return removeType(modelId, getIndividual(modelId, iid), getClass(cid));
	}
	
	/**
	 * Removes a ClassAssertion, where the class expression instantiated is an
	 * ObjectSomeValuesFrom expression
	 * 
	 * TODO - in fuure it should be possible to remove multiple assertions by leaving some fields null
	 * 
	 * @param modelId
	 * @param i
	 * @param p
	 * @param filler
	 * @return response info
	 */
	public OWLOperationResponse removeType(String modelId,
			OWLIndividual i, 
			OWLObjectPropertyExpression p,
			OWLClassExpression filler) {
		OWLClassAssertionAxiom axiom = 
				getOWLDataFactory(modelId).getOWLClassAssertionAxiom(
						getOWLDataFactory(modelId).getOWLObjectSomeValuesFrom(p, filler),
						i);
		return removeAxiom(modelId, axiom);
	}
	
	
	// TODO
//	public OWLOperationResponse removeTypes(String modelId,
//			OWLIndividual i, 
//			OWLObjectPropertyExpression p) {
//		return removeType(modelId, i, p, null);
//	}


	

	/**
	 * Convenience wrapper for {@link #addOccursIn(String, OWLIndividual, OWLClassExpression)}
	 * 
	 * @param modelId
	 * @param iid
	 * @param eid - e.g. PR:P12345
	 * @return response info
	 */
	public OWLOperationResponse addOccursIn(String modelId,
			String iid, String eid) {
		return addOccursIn(modelId, getIndividual(modelId, iid), getClass(eid));
	}

	/**
	 * Adds a ClassAssertion to the model, connecting an activity instance to the class of molecule
	 * that enables the activity.
	 * 
	 * Example: FGFR receptor activity occursIn some UniProtKB:FGF
	 * 
	 * The reasoner may detect an inconsistency under different scenarios:
	 *  - i may be an instance of a class that is disjoint with a bfo process
	 *  - the enabled may be an instance of a class that is disjoint with molecular entity
	 *  
	 *  Under these circumstances, no error is thrown, but the response code indicates that no operation
	 *  was performed on the kb, and the response object indicates the operation caused an inconsistency
	 * 
	 * @param modelId
	 * @param i
	 * @param enabler
	 * @return response info
	 */
	public OWLOperationResponse addOccursIn(String modelId,
			OWLIndividual i, 
			OWLClassExpression enabler) {
		return addType(modelId, i, OBOUpperVocabulary.BFO_occurs_in.getObjectProperty(getOntology()), enabler);
	}	

	/**
	 * Convenience wrapper for {@link #addEnabledBy(String, OWLIndividual, OWLClassExpression)}
	 * 
	 * @param modelId
	 * @param iid
	 * @param eid - e.g. PR:P12345
	 * @return response info
	 */
	public OWLOperationResponse addEnabledBy(String modelId,
			String iid, String eid) {
		return addEnabledBy(modelId, getIndividual(modelId, iid), getClass(eid));
	}

	/**
	 * Adds a ClassAssertion to the model, connecting an activity instance to the class of molecule
	 * that enables the activity.
	 * 
	 * Example: FGFR receptor activity enabledBy some UniProtKB:FGF
	 * 
	 * The reasoner may detect an inconsistency under different scenarios:
	 *  - i may be an instance of a class that is disjoint with a bfo process
	 *  - the enabled may be an instance of a class that is disjoint with molecular entity
	 *  
	 *  Under these circumstances, no error is thrown, but the response code indicates that no operation
	 *  was performed on the kb, and the response object indicates the operation caused an inconsistency
	 * 
	 * @param modelId
	 * @param i
	 * @param enabler
	 * @return response info
	 */
	public OWLOperationResponse addEnabledBy(String modelId,
			OWLIndividual i, 
			OWLClassExpression enabler) {
		return addType(modelId, i, OBOUpperVocabulary.GOREL_enabled_by.getObjectProperty(getOntology()), enabler);
	}	


	/**
	 * Adds triple (i,p,j) to specified model
	 * 
	 * @param modelId
	 * @param p
	 * @param i
	 * @param j
	 * @return response info
	 */
	public OWLOperationResponse addFact(String modelId, OWLObjectPropertyExpression p,
			OWLIndividual i, OWLIndividual j) {
		OWLObjectPropertyAssertionAxiom axiom = getOWLDataFactory(modelId).getOWLObjectPropertyAssertionAxiom(p, i, j);
		return addAxiom(modelId, axiom);
	}

	/**
	 * Convenience wrapper for {@link #addFact(String, OWLObjectPropertyExpression, OWLIndividual, OWLIndividual)}
	 *	
	 * @param modelId
	 * @param vocabElement
	 * @param i
	 * @param j
	 * @return response info
	 */
	public OWLOperationResponse addFact(String modelId, OBOUpperVocabulary vocabElement,
			OWLIndividual i, OWLIndividual j) {
		OWLObjectProperty p = vocabElement.getObjectProperty(getOntology());
		return addFact(modelId, p, i, j);
	}

	/**
	 * Convenience wrapper for {@link #addFact(String, OWLObjectPropertyExpression, OWLIndividual, OWLIndividual)}
	 * 
	 * @param modelId
	 * @param pid
	 * @param iid
	 * @param jid
	 * @return response info
	 */
	public OWLOperationResponse addFact(String modelId, String pid,
			String iid, String jid) {
		return addFact(modelId, getObjectProperty(pid), getIndividual(modelId, iid), getIndividual(modelId, jid));
	}

	/**
	 * Convenience wrapper for {@link #addFact(String, OWLObjectPropertyExpression, OWLIndividual, OWLIndividual)}
	 * 
	 * @param modelId
	 * @param vocabElement
	 * @param iid
	 * @param jid
	 * @return response info
	 */
	public OWLOperationResponse addFact(String modelId, OBOUpperVocabulary vocabElement,
			String iid, String jid) {
		return addFact(modelId, getObjectProperty(vocabElement), getIndividual(modelId, iid), getIndividual(modelId, jid));
	}
	
	/**
	 * @param modelId
	 * @param p
	 * @param i
	 * @param j
	 * @return response info
	 */
	public OWLOperationResponse removeFact(String modelId, OWLObjectPropertyExpression p,
			OWLIndividual i, OWLIndividual j) {
		OWLObjectPropertyAssertionAxiom axiom = getOWLDataFactory(modelId).getOWLObjectPropertyAssertionAxiom(p, i, j);
		return removeAxiom(modelId, axiom);
	}

	/**
	 * @param modelId
	 * @param vocabElement
	 * @param iid
	 * @param jid
	 * @return response info
	 */
	public OWLOperationResponse removeFact(String modelId, String pid,
			String iid, String jid) {
		return removeFact(modelId, 
				getObjectProperty(pid), 
				getIndividual(modelId, iid), getIndividual(modelId, jid));
	}


	
	/**
	 * Convenience wrapper for {@link #addPartOf(String, OWLIndividual, OWLIndividual)}
	 *
	 * @param modelId
	 * @param iid
	 * @param jid
	 * @return
	 */
	public OWLOperationResponse addPartOf(String modelId, 
			String iid, String jid) {
		return addPartOf(modelId, getIndividual(modelId, iid), getIndividual(modelId, jid));
	}

	/**
	 * Adds an OWL ObjectPropertyAssertion connecting i to j via part_of
	 * 
	 * Note that the inverse assertion is entailed, but not asserted
	 * 
	 * @param modelId
	 * @param i
	 * @param j
	 * @return response info
	 */
	public OWLOperationResponse addPartOf(String modelId,
			OWLIndividual i, OWLIndividual j) {
		return addFact(modelId, getObjectProperty(OBOUpperVocabulary.BFO_part_of), i, j);
	}



	/**
	 * In general, should not be called directly - use a wrapper method
	 * 
	 * @param modelId
	 * @param axiom
	 * @return response info
	 */
	public OWLOperationResponse addAxiom(String modelId, OWLAxiom axiom) {
		LegoModelGenerator model = getModel(modelId);
		OWLOntology ont = model.getAboxOntology();
		boolean isConsistentAtStart = model.getReasoner().isConsistent();
		AddAxiom change = new AddAxiom(ont, axiom);
		ont.getOWLOntologyManager().applyChange(change);
		// TODO - track axioms to allow redo
		model.getReasoner().flush();
		boolean isConsistentAtEnd = model.getReasoner().isConsistent();
		if (isConsistentAtStart && !isConsistentAtEnd) {
			// rollback
			ont.getOWLOntologyManager().removeAxiom(ont, axiom);
			return new OWLOperationResponse(change, false, true);

		}
		else {
			return new OWLOperationResponse(change, true, false);
		}

	}
	
	/**
	 * In general, should not be called directly - use a wrapper method
	 * 
	 * TODO: an error should be returned if the user attempts to remove
	 * any inferred axiom. For example, if f1 part_of p1, and p1 Type occurs_in some cytosol,
	 * and the user attempts to delete "located in cytosol", the axiom will "come back"
	 * as it is inferred. 
	 * 
	 * @param modelId
	 * @param axiom
	 * @return response info
	 */
	public OWLOperationResponse removeAxiom(String modelId, OWLAxiom axiom) {
		LegoModelGenerator model = getModel(modelId);
		OWLOntology ont = model.getAboxOntology();
		// TODO - check axiom exists
		RemoveAxiom change = new RemoveAxiom(ont, axiom);
		ont.getOWLOntologyManager().applyChange(change);
		// TODO - track axioms to allow redo
		return new OWLOperationResponse(true);
	}	

	public OWLOperationResponse undo(String modelId, String chageId) {
		LOG.error("Not implemented");
		return null;
	}
	
	/**
	 * TODO: decide identifier policy for models
	 * 
	 * @param p
	 * @param db
	 * @return identifier
	 */
	private String getModelId(String p, String db) {
		return "gomodel:"+db + "-"+p.replaceAll(":", "-");
	}
	
	
	protected abstract class LegoStringDotRenderer extends LegoDotWriter {
		public LegoStringDotRenderer(OWLGraphWrapper graph, OWLReasoner reasoner) {
			super(graph, reasoner);
			// TODO Auto-generated constructor stub
		}

		public StringBuffer sb = new StringBuffer();
		
	}
	
	/**
	 * For testing purposes - may be obsoleted with rendering moved to client
	 * 
	 * @param modelId
	 * @return dot string
	 * @throws IOException
	 * @throws UnExpectedStructureException
	 */
	public String generateDot(String modelId) throws IOException, UnExpectedStructureException {
		LegoModelGenerator m = getModel(modelId);
		Set<OWLNamedIndividual> individuals = getIndividuals(modelId);
	
		LegoStringDotRenderer renderer = 
				new LegoStringDotRenderer(graph, m.getReasoner()) {


			@Override
			protected void open() throws IOException {
			}

			@Override
			protected void close() {
			}

			@Override
			protected void appendLine(CharSequence line) throws IOException {
				//System.out.println(line);
				sb.append(line).append('\n');
			}
		};
		renderer.render(individuals, modelId, true);
		return renderer.sb.toString();
	}
	
	/**
	 * @param modelId
	 * @return 
	 * @throws IOException 
	 * @throws UnExpectedStructureException 
	 * @throws InterruptedException 
	 */
	public File generateImage(String modelId) throws IOException, UnExpectedStructureException, InterruptedException {
		final File dotFile = File.createTempFile("LegoAnnotations", ".dot");
		final File pngFile = File.createTempFile("LegoAnnotations", ".png");

		LegoModelGenerator m = getModel(modelId);
		Set<OWLNamedIndividual> individuals = getIndividuals(modelId);
		OWLReasoner reasoner = m.getReasoner();
		String dotPath = "/opt/local/bin/dot"; // TODO
		try {
			// Step 1: render dot file
			LegoRenderer dotWriter = new LegoDotWriter(graph, reasoner) {
				
				private PrintWriter writer = null;
				
				@Override
				protected void open() throws IOException {
					writer = new PrintWriter(dotFile);
				}
				
				@Override
				protected void appendLine(CharSequence line) throws IOException {
					writer.println(line);
				}

				@Override
				protected void close() {
					IOUtils.closeQuietly(writer);
				}
				
			};
			dotWriter.render(individuals, null, true);
			
			// Step 2: render png file using graphiz (i.e. dot)
			Runtime r = Runtime.getRuntime();

			final String in = dotFile.getAbsolutePath();
			final String out = pngFile.getAbsolutePath();
			
			Process process = r.exec(dotPath + " " + in + " -Tpng -q -o " + out);

			process.waitFor();
			
			return pngFile;
		} finally {
			// delete temp files, do not rely on deleteOnExit
			FileUtils.deleteQuietly(dotFile);
			FileUtils.deleteQuietly(pngFile);
		}
	
	}

	/**
	 * @param ontology
	 * @param output
	 * @param name
	 * @throws Exception
	 */
	public void writeLego(OWLOntology ontology, final String output, String name) throws Exception {

		Set<OWLNamedIndividual> individuals = ontology.getIndividualsInSignature(true);


		LegoRenderer renderer = 
				new LegoDotWriter(graph, molecularModelGenerator.getReasoner()) {

			BufferedWriter fileWriter = null;

			@Override
			protected void open() throws IOException {
				fileWriter = new BufferedWriter(new FileWriter(new File(output)));
			}

			@Override
			protected void close() {
				IOUtils.closeQuietly(fileWriter);
			}

			@Override
			protected void appendLine(CharSequence line) throws IOException {
				//System.out.println(line);
				fileWriter.append(line).append('\n');
			}
		};
		renderer.render(individuals, name, true);

	}
	

}
