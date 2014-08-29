package owltools.gaf.lego;

import static org.junit.Assert.*;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.commons.io.FileUtils;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.junit.Test;
import org.semanticweb.elk.owlapi.ElkReasonerFactory;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.AddImport;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLImportsDeclaration;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLObjectSomeValuesFrom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyManager;

import owltools.gaf.GafDocument;
import owltools.gaf.parser.GafObjectsBuilder;
import owltools.graph.OWLGraphWrapper;
import owltools.io.CatalogXmlIRIMapper;
import owltools.util.ModelContainer;
import owltools.vocab.OBOUpperVocabulary;

public class PombaseLegoModelGeneratorTest extends AbstractLegoModelGeneratorTest {
	private static Logger LOG = Logger.getLogger(PombaseLegoModelGeneratorTest.class);

	static{
		Logger.getLogger("org.semanticweb.elk").setLevel(Level.ERROR);
	}

	@Test
	public void testPombeImports() throws Exception {
		OWLOntologyManager m = OWLManager.createOWLOntologyManager();
		
		OWLOntology tbox = 
				m.loadOntologyFromOntologyDocument(getResource("go-iron-transport-subset.owl"));
		
		OWLOntology abox = m.createOntology(IRI.create("foo/bar"));
		createImports(abox,
				tbox.getOntologyID().getOntologyIRI(),
			IRI.create("http://purl.obolibrary.org/obo/ro.owl"),
			IRI.create("http://purl.obolibrary.org/obo/go/extensions/ro_pending.owl"));
		
		g = new OWLGraphWrapper(tbox);
		OWLClass p = g.getOWLClassByIdentifier("GO:0033215"); // iron
		assertNotNull(p);
		
		ModelContainer model = new ModelContainer(tbox, abox);
		LegoModelGenerator molecularModelGenerator = new LegoModelGenerator(model);
		
		molecularModelGenerator.setPrecomputePropertyClassCombinations(false);
		Set<String> seedGenes = new HashSet<String>();
		
		parseGAF("pombase-test.gaf");
		GafObjectsBuilder builder = new GafObjectsBuilder();
		GafDocument ppidoc = builder.buildDocument(getResource("pombase-test-ppi.gaf"));
		gafdoc.getGeneAnnotations().addAll(ppidoc.getGeneAnnotations());
		
		molecularModelGenerator.initialize(gafdoc, g);
		seedGenes.addAll(molecularModelGenerator.getGenes(p));
		molecularModelGenerator.setContextualizingSuffix("test");
		
		
		molecularModelGenerator.buildNetwork(p, seedGenes);
		
		Collection<OWLNamedIndividual> individuals = molecularModelGenerator.getGeneratedIndividuals();
		System.out.println("constructed imports, individual count: "+individuals.size());
		for (OWLNamedIndividual i : individuals) {
			System.out.println(i.getIRI().toString());
		}
		FileOutputStream os = new FileOutputStream(new File("target/aonti-imports.owl"));
		m.saveOntology(model.getAboxOntology(), os);

		assertEquals(7, individuals.size());
	}
	
	private void createImports(OWLOntology ont, IRI...imports) throws OWLOntologyCreationException {
		OWLOntologyManager m = ont.getOWLOntologyManager();
		OWLDataFactory f = m.getOWLDataFactory();
		for (IRI importIRI : imports) {
			OWLImportsDeclaration importDeclaration = f.getOWLImportsDeclaration(importIRI);
			m.loadOntology(importIRI);
			m.applyChange(new AddImport(ont, importDeclaration));
		}
	}
	
	@Test
	public void testPombe() throws Exception {
		OWLOntologyManager m = OWLManager.createOWLOntologyManager();
		m.addIRIMapper(new CatalogXmlIRIMapper(getResource("catalog-v001.xml")));
		OWLOntology tbox = 
				m.loadOntologyFromOntologyDocument(getResource("go-iron-transport-subset-importer.owl"));
		//g = pw.parseToOWLGraph(getResourceIRIString("go-iron-transport-subset-importer.owl"));
		g = new OWLGraphWrapper(tbox);
		
		//ParserWrapper pw = new ParserWrapper();
		FileUtils.forceMkdir(new File("target/lego"));
		w = new FileWriter(new File("target/lego.out"));


		parseGAF("pombase-test.gaf");
		GafObjectsBuilder builder = new GafObjectsBuilder();
		GafDocument ppidoc = builder.buildDocument(getResource("pombase-test-ppi.gaf"));
		gafdoc.getGeneAnnotations().addAll(ppidoc.getGeneAnnotations());

		//System.out.println("gMGR = "+pw.getManager());

		mc = model = new ModelContainer(g.getSourceOntology(), new ElkReasonerFactory());
		mmg = ni = new LegoModelGenerator(model);
		ni.initialize(gafdoc, g);
		//ni.getOWLOntologyManager().removeOntology(ni.getAboxOntology());

		int aboxImportsSize = model.getAboxOntology().getImportsClosure().size();
		int qboxImportsSize = model.getQueryOntology().getImportsClosure().size();

		LOG.info("Abox ontology imports: "+aboxImportsSize);
		LOG.info("Q ontology imports: "+qboxImportsSize);
		//assertEquals(2, aboxImportsSize);
		//assertEquals(3, qboxImportsSize);


		OWLClass p = g.getOWLClassByIdentifier("GO:0033215"); // iron

		int nSups = model.getReasoner().getSuperClasses(p, false).getFlattened().size();
		LOG.info("supers(p) = "+nSups);
		assertEquals(22, nSups);

		//ni = new LegoGenerator(g.getSourceOntology(), new ElkReasonerFactory());
		//ni.initialize(gafdoc, g);

		Set<String> seedGenes = ni.getGenes(p);


		LOG.info("\n\nP="+render(p));
		ni.buildNetwork(p, seedGenes);

		Map<String, Object> stats = ni.getGraphStatistics();
		for (String k : stats.keySet()) {
			writeln("# "+k+" = "+stats.get(k));
		}


		for (String gene : seedGenes) {
			writeln("  SEED="+render(gene));
		}
		
		this.expectedOPAs("OPA", null);
		
		this.expectedIndividiuals(getClass(OBOUpperVocabulary.GO_molecular_function), 3);
		this.expectedIndividiuals(getClass(OBOUpperVocabulary.GO_biological_process), 4);


		ni.extractModule();
		saveByClass(p);
		
		OWLObjectProperty ENABLED_BY = 
				OBOUpperVocabulary.GOREL_enabled_by.getObjectProperty(m.getOWLDataFactory());
		
		Set<OWLNamedIndividual> mfinds =
				model.getReasoner().getInstances(getClass(OBOUpperVocabulary.GO_molecular_function), 
						false).getFlattened();
		int nGPs = 0;
		for (OWLNamedIndividual i : mfinds) {
			for (OWLClassExpression cx : i.getTypes(model.getAboxOntology())) {
				if (cx instanceof OWLObjectSomeValuesFrom) {
					OWLObjectSomeValuesFrom svf = (OWLObjectSomeValuesFrom)cx;
					if (svf.getProperty().equals(ENABLED_BY)) {
						//System.out.println("MF "+i+" ==> "+cx);
						nGPs++;
					}
					
				}
			}
		}
		assertEquals(3, nGPs);
		
		//			OWLOntology ont = ni.getAboxOntology();
		//			String pid = g.getIdentifier(p);
		//			String fn = pid.replaceAll(":", "_") + ".owl";
		//			FileOutputStream os = new FileOutputStream(new File("target/lego/"+fn));
		//			ont.getOWLOntologyManager().saveOntology(ont, os);
		//			ont.getOWLOntologyManager().removeOntology(ont);

		FileOutputStream os = new FileOutputStream(new File("target/qont.owl"));
		m.saveOntology(model.getQueryOntology(), os);
		os.close();
		
		os = new FileOutputStream(new File("target/aont.owl"));
		model.getQueryOntology().getOWLOntologyManager().saveOntology(model.getAboxOntology(), os);

		w.close();

		LOG.info("Num generated individuals = "+ni.getGeneratedIndividuals().size());
		assertEquals(7, ni.getGeneratedIndividuals().size());
		LOG.info("Score = "+ni.ccp);



	}



}
