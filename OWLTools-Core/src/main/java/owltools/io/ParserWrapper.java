package owltools.io;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.commons.io.IOUtils;
import org.apache.log4j.Logger;
import org.coode.owlapi.obo.parser.OBOOntologyFormat;
import org.obolibrary.obo2owl.Owl2Obo;
import org.obolibrary.oboformat.model.Frame;
import org.obolibrary.oboformat.model.OBODoc;
import org.obolibrary.oboformat.parser.OBOFormatConstants.OboFormatTag;
import org.obolibrary.oboformat.writer.OBOFormatWriter;
import org.obolibrary.oboformat.writer.OBOFormatWriter.NameProvider;
import org.obolibrary.oboformat.writer.OBOFormatWriter.OBODocNameProvider;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.io.OWLParserFactory;
import org.semanticweb.owlapi.io.OWLParserFactoryRegistry;
import org.semanticweb.owlapi.io.RDFXMLOntologyFormat;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLObject;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyAlreadyExistsException;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyDocumentAlreadyExistsException;
import org.semanticweb.owlapi.model.OWLOntologyFormat;
import org.semanticweb.owlapi.model.OWLOntologyID;
import org.semanticweb.owlapi.model.OWLOntologyIRIMapper;
import org.semanticweb.owlapi.model.OWLOntologyLoaderListener;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.OWLOntologyStorageException;

import owltools.graph.OWLGraphWrapper;

/**
 * Convenience class wrapping org.oboformat that abstracts away underlying details of ontology format or location
 * @author cjm
 *
 */
public class ParserWrapper {

	private static Logger LOG = Logger.getLogger(ParserWrapper.class);
	OWLOntologyManager manager;
	private final List<OWLOntologyIRIMapper> mappers = new ArrayList<OWLOntologyIRIMapper>();
	OBODoc obodoc;
	boolean isCheckOboDoc = true;
	
	
	public ParserWrapper() {
		manager = OWLManager.createOWLOntologyManager(); // persist?
		removeOldOwlApiObo(manager);
		OWLOntologyLoaderListener listener = new OWLOntologyLoaderListener() {

			@Override
			public void startedLoadingOntology(LoadingStartedEvent event) {
				IRI id = event.getOntologyID().getOntologyIRI();
				IRI source = event.getDocumentIRI();
				LOG.info("Start loading ontology: "+id+" from: "+source);
			}

			@Override
			public void finishedLoadingOntology(LoadingFinishedEvent event) {
				IRI id = event.getOntologyID().getOntologyIRI();
				IRI source = event.getDocumentIRI();
				LOG.info("Finished loading ontology: "+id+" from: "+source);
			}
		};
		manager.addOntologyLoaderListener(listener);
	}
	
	/**
	 * This will try to remove the old OBO parser from the OWL-API.
	 * 
	 * @param manager
	 */
	public static void removeOldOwlApiObo(OWLOntologyManager manager) {
		synchronized (manager) {
			OWLParserFactoryRegistry registry = OWLParserFactoryRegistry.getInstance();
			List<OWLParserFactory> factories = new ArrayList<OWLParserFactory>(registry.getParserFactories());
			for (OWLParserFactory parserFactory : factories) {
				if (parserFactory.getClass().getName().equals("org.coode.owlapi.obo12.parser.OBO12ParserFactory")) {
					registry.unregisterParserFactory(parserFactory);
				}
			}
		}
	}
	
	public OWLOntologyManager getManager() {
		return manager;
	}
	public void setManager(OWLOntologyManager manager) {
		this.manager = manager;
	}
	
	public boolean isCheckOboDoc() {
		return isCheckOboDoc;
	}

	public void setCheckOboDoc(boolean isCheckOboDoc) {
		this.isCheckOboDoc = isCheckOboDoc;
	}

	public void addIRIMapper(OWLOntologyIRIMapper mapper) {
		manager.addIRIMapper(mapper);
		mappers.add(0, mapper);
	}
	public void removeIRIMapper(OWLOntologyIRIMapper mapper) {
		manager.removeIRIMapper(mapper);
		mappers.remove(mapper);
	}
	public List<OWLOntologyIRIMapper> getIRIMappers() {
		return Collections.unmodifiableList(mappers);
	}
	public void addIRIMappers(List<OWLOntologyIRIMapper> mappers) {
		List<OWLOntologyIRIMapper> reverse = new ArrayList<OWLOntologyIRIMapper>(mappers);
		Collections.reverse(reverse);
		for (OWLOntologyIRIMapper mapper : reverse) {
			addIRIMapper(mapper);
		}
	}
	
	public OWLGraphWrapper parseToOWLGraph(String iriString) throws OWLOntologyCreationException, IOException {
		return new OWLGraphWrapper(parse(iriString));		
	}
	
	@SuppressWarnings("deprecation")
	public OWLGraphWrapper parseToOWLGraph(String iriString, boolean isMergeImportClosure) throws OWLOntologyCreationException, IOException {
		return new OWLGraphWrapper(parse(iriString), isMergeImportClosure);		
	}

	public OWLOntology parse(String iriString) throws OWLOntologyCreationException, IOException {
		return parseOWL(iriString);
	}
	
	public OWLOntology parseOBO(String source) throws IOException, OWLOntologyCreationException {
		return parseOWL(source);
	}
	
	public OWLOntology parseOWL(String iriString) throws OWLOntologyCreationException {
		IRI iri;
		if (LOG.isDebugEnabled()) {
			LOG.debug("parsing: "+iriString);
		}
		if (isIRI(iriString)) {
			iri = IRI.create(iriString);
		}
		else {
			iri = IRI.create(new File(iriString));
		}
		return parseOWL(iri);
	}
	
	private boolean isIRI(String iriString) {
		return iriString.startsWith("file:") || iriString.startsWith("http:") || iriString.startsWith("https:");
	}

	public OWLOntology parseOWL(IRI iri) throws OWLOntologyCreationException {
		if (LOG.isDebugEnabled()) {
			LOG.debug("parsing: "+iri.toString()+" using "+manager);
		}
		OWLOntology ont;
		try {
			ont = manager.loadOntology(iri);
		} catch (OWLOntologyAlreadyExistsException e) {
			// Trying to recover from exception
			OWLOntologyID ontologyID = e.getOntologyID();
			ont = manager.getOntology(ontologyID);
			if (ont == null) {
				// throw original exception, if no ontology could be found
				// never return null ontology
				throw e;
			}
			LOG.info("Skip already loaded ontology: "+iri);
		} catch (OWLOntologyDocumentAlreadyExistsException e) {
			// Trying to recover from exception
			IRI duplicate = e.getOntologyDocumentIRI();
			ont = manager.getOntology(duplicate);
			if (ont == null) {
				for(OWLOntology managed : manager.getOntologies()) {
					if(duplicate.equals(managed.getOntologyID().getOntologyIRI())) {
						LOG.info("Skip already loaded ontology: "+iri);
						ont = managed;
						break;
					}
				}
			}
			if (ont == null) {
				// throw original exception, if no ontology could be found
				// never return null ontology
				throw e;
			}
		}
		return ont;
	}

	public void saveOWL(OWLOntology ont, String file) throws OWLOntologyStorageException {
		OWLOntologyFormat owlFormat = new RDFXMLOntologyFormat();
		saveOWL(ont, owlFormat, file);
	}
	public void saveOWL(OWLOntology ont, OWLOntologyFormat owlFormat, String file) throws OWLOntologyStorageException {
		if ((owlFormat instanceof OBOOntologyFormat) || (owlFormat instanceof OWLJSONFormat)) {
			try {
				FileOutputStream os = new FileOutputStream(new File(file));
				saveOWL(ont, owlFormat, os);
			} catch (FileNotFoundException e) {
				throw new OWLOntologyStorageException("Could not open file: "+file, e);
			}
		}
		else {
			IRI iri;
			if (file.startsWith("file://")) {
				iri = IRI.create(file);
			}
			else {
				iri = IRI.create(new File(file));
			}
			manager.saveOntology(ont, owlFormat, iri);
		}
	}
	public void saveOWL(OWLOntology ont, OWLOntologyFormat owlFormat,
			OutputStream outputStream) throws OWLOntologyStorageException {
		if (owlFormat instanceof OBOOntologyFormat && this.isCheckOboDoc == false) {
			// special work-around for skipping the OBO validation before write
			// see also OWL-API issue: https://github.com/owlcs/owlapi/issues/290
			// see also saveOWL(OWLOntology, OWLOntologyFormat, String) for redundant code
			Owl2Obo bridge = new Owl2Obo();
			OBODoc doc;
			BufferedWriter bw = null;
			try {
				doc = bridge.convert(ont);
				OBOFormatWriter oboWriter = new OBOFormatWriter();
				oboWriter.setCheckStructure(isCheckOboDoc); 
				bw = new BufferedWriter(new OutputStreamWriter(outputStream));
				oboWriter.write(doc, bw);
			} catch (IOException e) {
				throw new OWLOntologyStorageException("Could not write ontology to output stream.", e);
			}
			finally {
				IOUtils.closeQuietly(bw);
			}
		}
		else if (owlFormat instanceof OWLJSONFormat) {
			
			BufferedWriter bw = null;
			try {
				bw = new BufferedWriter(new OutputStreamWriter(outputStream));
				OWLGsonRenderer gr = new OWLGsonRenderer(new PrintWriter(outputStream));
				gr.render(ont);
				gr.flush();
			}
			finally {
				IOUtils.closeQuietly(bw);
			}
		}
		else {
			manager.saveOntology(ont, owlFormat, outputStream);
		}
	}
	
	public OBODoc getOBOdoc() {
		return obodoc;
	}

	/**
	 * Provide names for the {@link OBOFormatWriter} using an
	 * {@link OWLGraphWrapper}.
	 * 
	 * @see OboAndOwlNameProvider use the {@link OboAndOwlNameProvider}, the
	 *      pure OWL lookup is problematic for relations.
	 */
	public static class OWLGraphWrapperNameProvider implements NameProvider {
		private final OWLGraphWrapper graph;
		private final String defaultOboNamespace;

		/**
		 * @param graph
		 */
		public OWLGraphWrapperNameProvider(OWLGraphWrapper graph) {
			super();
			this.graph = graph;
			this.defaultOboNamespace = null;
			
		}
		
		/**
		 * @param graph
		 * @param defaultOboNamespace
		 */
		public OWLGraphWrapperNameProvider(OWLGraphWrapper graph, String defaultOboNamespace) {
			super();
			this.graph = graph;
			this.defaultOboNamespace = defaultOboNamespace;
			
		}
		
		/**
		 * @param graph
		 * @param oboDoc
		 * 
		 * If an {@link OBODoc} is available use {@link OboAndOwlNameProvider}.
		 */
		@Deprecated
		public OWLGraphWrapperNameProvider(OWLGraphWrapper graph, OBODoc oboDoc) {
			super();
			this.graph = graph;
			String defaultOboNamespace = null;
			if (oboDoc != null) {
				Frame headerFrame = oboDoc.getHeaderFrame();
				if (headerFrame != null) {
					defaultOboNamespace = headerFrame.getTagValue(OboFormatTag.TAG_DEFAULT_NAMESPACE, String.class);
				}
			}
			this.defaultOboNamespace = defaultOboNamespace;
			
		}

		@Override
		public String getName(String id) {
			String name = null;
			OWLObject obj = graph.getOWLObjectByIdentifier(id);
			if (obj != null) {
				name = graph.getLabel(obj);
			}
			return name;
		}

		@Override
		public String getDefaultOboNamespace() {
			return defaultOboNamespace;
		}
	}
	
	/**
	 * Provide names for the {@link OBOFormatWriter} using an {@link OBODoc}
	 * first and an {@link OWLGraphWrapper} as secondary.
	 */
	public static class OboAndOwlNameProvider extends OBODocNameProvider {

		private final OWLGraphWrapper graph;
		
		public OboAndOwlNameProvider(OBODoc oboDoc, OWLGraphWrapper wrapper) {
			super(oboDoc);
			this.graph = wrapper;
		}

		@Override
		public String getName(String id) {
			String name = super.getName(id);
			if (name != null) {
				return name;
			}
			OWLObject owlObject = graph.getOWLObjectByIdentifier(id);
			if (owlObject != null) {
				name = graph.getLabel(owlObject);
			}
			return name;
		}

	}
	
}