package owltools.gaf.lego;

import java.io.File;
import java.io.IOException;
import java.io.Writer;

import org.apache.commons.io.FileUtils;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLObject;
import org.semanticweb.owlapi.model.OWLOntologyStorageException;

import owltools.gaf.GafDocument;
import owltools.gaf.parser.GafObjectsBuilder;
import owltools.graph.OWLGraphWrapper;
import owltools.util.AbstractMinimalModelGeneratorTest;
import owltools.util.ModelContainer;

public abstract class AbstractLegoModelGeneratorTest extends AbstractMinimalModelGeneratorTest {
	private static Logger LOG = Logger.getLogger(AbstractLegoModelGeneratorTest.class);

	static{
		Logger.getLogger("org.semanticweb.elk").setLevel(Level.ERROR);
		//Logger.getLogger("org.semanticweb.elk.reasoner.indexing.hierarchy").setLevel(Level.ERROR);
	}
	ModelContainer model;
	LegoModelGenerator ni;
	Writer w;
	OWLGraphWrapper g;
	GafDocument gafdoc;

	
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

	protected void parseGAF(String fn) throws IOException {
		GafObjectsBuilder builder = new GafObjectsBuilder();
		gafdoc = builder.buildDocument(getResource(fn));
	}

	protected void saveByClass(OWLClass p) throws OWLOntologyStorageException, IOException {
		FileUtils.forceMkdir(new File("target/lego"));

		String pid = g.getIdentifier(p);
		String fn = "lego/"+pid.replaceAll(":", "_");
		LOG.info("Saving to: "+fn);
		save(fn);		
	}


}
