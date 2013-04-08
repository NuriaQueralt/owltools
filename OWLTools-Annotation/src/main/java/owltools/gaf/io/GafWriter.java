package owltools.gaf.io;

import java.io.BufferedOutputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.PrintStream;

import org.semanticweb.owlapi.model.OWLObject;

import owltools.gaf.Bioentity;
import owltools.gaf.GafDocument;
import owltools.gaf.GeneAnnotation;
import owltools.graph.OWLGraphWrapper;
import owltools.io.OWLPrettyPrinter;

public class GafWriter  {

	protected OWLPrettyPrinter prettyPrinter;
	protected PrintStream stream;

	

	public GafWriter() {
		super();
	}
	
	public PrintStream getStream() {
		return stream;
	}

	public void setStream(PrintStream stream) {
		this.stream = stream;
	}
	
	public void setStream(String file) {
		FileOutputStream fos;
		try {
			fos = new FileOutputStream(file);
			this.stream = new PrintStream(new BufferedOutputStream(fos));
		} catch (FileNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
	
	public void write(GafDocument gdoc) {
		writeHeader(gdoc);
		for (GeneAnnotation ann: gdoc.getGeneAnnotations()) {
			write(ann);
		}
		stream.close();
	}
	

	public void writeHeader(GafDocument gdoc) {
		print("!gaf-version: 2.0\n");
		for (String comment : gdoc.getComments()) {
			print("! "+comment+"\n");
		}
	}

	public void write(GeneAnnotation ann) {
		Bioentity e = ann.getBioentityObject();
		print(e.getDb());
		sep();
		print(e.getLocalId());
		sep();
		print(e.getSymbol());
		sep();
		print(ann.getCompositeQualifier());
		sep();
		print(ann.getCls());
		sep();
		print(ann.getReferenceId());
		sep();
		print(ann.getWithExpression());
		sep();
		print(ann.getWithExpression());
		sep();
		print("P"); // todo
		sep();
		print(e.getFullName());
		sep();
		print(""); // syns - todo
		sep();
		print(e.getTypeCls());
		sep();
		String taxon = e.getNcbiTaxonId().replaceAll("NCBITaxon", "taxon");
		print(taxon);
		sep();
		print(ann.getLastUpdateDate());
		sep();
		print(ann.getAssignedBy());
		sep();
		print(ann.getExtensionExpression());
		sep();
		print(ann.getGeneProductForm());
		nl();
	}

	protected void print(String s) {
		stream.print(s);
	}


	protected void sep() {
		stream.print("\t");
	}

	protected void nl() {
		stream.print("\n");
	}

}
