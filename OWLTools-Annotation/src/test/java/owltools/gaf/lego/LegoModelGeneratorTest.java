package owltools.gaf.lego;

import static org.junit.Assert.*;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.Map;
import java.util.Set;

import org.apache.commons.io.FileUtils;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.junit.Test;
import org.semanticweb.elk.owlapi.ElkReasonerFactory;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLObject;
import org.semanticweb.owlapi.model.OWLOntology;

import owltools.OWLToolsTestBasics;
import owltools.gaf.GafDocument;
import owltools.gaf.GafObjectsBuilder;
import owltools.graph.OWLGraphWrapper;
import owltools.io.ParserWrapper;
import owltools.util.MinimalModelGeneratorTest;

public class LegoModelGeneratorTest extends OWLToolsTestBasics {
	private static Logger LOG = Logger.getLogger(LegoModelGeneratorTest.class);

	static{
		Logger.getLogger("org.semanticweb.elk").setLevel(Level.ERROR);
		//Logger.getLogger("org.semanticweb.elk.reasoner.indexing.hierarchy").setLevel(Level.ERROR);
	}
	LegoModelGenerator ni;
	Writer w;
	
	@Test
	public void testPombe() throws Exception {
		ParserWrapper pw = new ParserWrapper();
		FileUtils.forceMkdir(new File("target/lego"));
		w = new FileWriter(new File("target/lego.out"));
		
		OWLGraphWrapper g = pw.parseToOWLGraph(getResourceIRIString("go-iron-transport-subset.obo"));

		GafObjectsBuilder builder = new GafObjectsBuilder();
		GafDocument gafdoc = builder.buildDocument(getResource("pombase-test.gaf"));
		GafDocument ppidoc = builder.buildDocument(getResource("pombase-test-ppi.gaf"));
		gafdoc.getGeneAnnotations().addAll(ppidoc.getGeneAnnotations());
		System.out.println("gMGR = "+pw.getManager());
		ni = new LegoModelGenerator(g.getSourceOntology(), new ElkReasonerFactory());
		ni.initialize(gafdoc, g);
		//ni.getOWLOntologyManager().removeOntology(ni.getAboxOntology());

		LegoModelGenerator mmg = ni;
		int aboxImportsSize = mmg.getAboxOntology().getImportsClosure().size();
		int qboxImportsSize = mmg.getQueryOntology().getImportsClosure().size();

		LOG.info("Abox ontology imports: "+aboxImportsSize);
		LOG.info("Q ontology imports: "+qboxImportsSize);
		assertEquals(2, aboxImportsSize);
		assertEquals(3, qboxImportsSize);

		LOG.info("#process classes in test = "+ni.processClassSet.size());
		assertEquals(37, ni.processClassSet.size());
		for (OWLClass p : ni.processClassSet) {
			if (!g.getIdentifier(p).equals("GO:0033215"))
				continue;
			
			int nSups = ni.getReasoner().getSuperClasses(p, false).getFlattened().size();
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

			ni.extractModule();
			OWLOntology ont = ni.getAboxOntology();
			String pid = g.getIdentifier(p);
			String fn = pid.replaceAll(":", "_") + ".owl";
			FileOutputStream os = new FileOutputStream(new File("target/lego/"+fn));
			ont.getOWLOntologyManager().saveOntology(ont, os);
			ont.getOWLOntologyManager().removeOntology(ont);
		}
		FileOutputStream os = new FileOutputStream(new File("target/qont.owl"));
		ni.getQueryOntology().getOWLOntologyManager().saveOntology(ni.getQueryOntology(), os);
		
		w.close();
		
		LOG.info("Num generated individuals = "+ni.getGeneratedIndividuals().size());
		assertEquals(7, ni.getGeneratedIndividuals().size());
		LOG.info("Score = "+ni.ccp);
		
		
		
	}
	
	protected void write(String s) throws IOException {
		w.append(s);
	}
	protected void writeln(String s) throws IOException {
		LOG.info(s);
		w.append(s + "\n");
	}

	protected String render(String x) {
		return x + " ! " + ni.getLabel(x);
	}
	protected String render(OWLObject x) {
		if (x == null) {
			return "null";
		}
		return x + " ! " + ni.getLabel(x);
	}


}
