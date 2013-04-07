package owltools.gaf.owl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.log4j.Logger;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLEquivalentClassesAxiom;
import org.semanticweb.owlapi.model.OWLObjectIntersectionOf;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLObjectSomeValuesFrom;
import org.semanticweb.owlapi.model.OWLOntology;

import owltools.gaf.ExtensionExpression;
import owltools.gaf.GafDocument;
import owltools.gaf.GeneAnnotation;
import owltools.graph.OWLGraphWrapper;

/**
 * @author cjm
 * given an annotation to a pre-existing term,
 * this will add to the annotation extension field based on existing logical definitions.
 * 
 * optionally, the original annotation can be replaced with the more basic term (genus)
 * 
 * In OWL terminology, this operation is known as "unfolding" - we are unfolding or unpacking
 * a composite class into its constituent components.
 * 
 *  See:
 * http://code.google.com/p/owltools/wiki/AnnotationExtensionFolding
 */
public class AnnotationExtensionUnfolder extends GAFOWLBridge {

	private static final Logger LOG = Logger.getLogger(AnnotationExtensionUnfolder.class);
	
	int lastId = 0;
	public boolean isReplaceGenus = false;

	public AnnotationExtensionUnfolder(OWLGraphWrapper g) {
		super(g);
	}

	public void unfold(GafDocument gdoc) throws MultipleUnfoldOptionsException {
		List<GeneAnnotation> newAnns = new ArrayList<GeneAnnotation>();
		int n = 0;
		for (GeneAnnotation ann : gdoc.getGeneAnnotations()) {
			Collection<GeneAnnotation> replacedAnns = unfold(gdoc, ann);
			if (replacedAnns != null && replacedAnns.size() > 0) {
				newAnns.addAll(replacedAnns);
				n += replacedAnns.size();
			}
			else {
				newAnns.add(ann);
			}
		}
		gdoc.setGeneAnnotations(newAnns);
		gdoc.addComment("This GAF has been processed using AnnotationExtensionUnfolder");
		gdoc.addComment("Number expansions: "+n);
		
	}

	/**
	 * given an annotation to a pre-existing term, 
	 * this will return a set of zero or more annotations to new terms that are generated from
	 * folding the annotation extensions into newly created term 
	 * 
	 * @param gdoc
	 * @param ann
	 * @return
	 * @throws MultipleUnfoldOptionsException 
	 */
	public Collection<GeneAnnotation> unfold(GafDocument gdoc, GeneAnnotation ann) throws MultipleUnfoldOptionsException {
		List<GeneAnnotation> newAnns = new ArrayList<GeneAnnotation>();
		OWLDataFactory fac = graph.getDataFactory();
		OWLOntology ont = graph.getSourceOntology();
		OWLClass annotatedToClass = getOWLClass(ann.getCls());
		// c16
		Collection<ExtensionExpression> preExistingExtExprs = ann.getExtensionExpressions();

		OWLClassExpression x = unfold(ont, annotatedToClass);

		Set<ExtensionExpression> exts = new HashSet<ExtensionExpression>();
		OWLClass genus = null;
		if (x != null) {
			if (x instanceof OWLObjectIntersectionOf) {
				for (OWLClassExpression op : ((OWLObjectIntersectionOf)x).getOperands()) {
					if (op instanceof OWLClass) {
						genus = (OWLClass) op;
					}
					else if (op instanceof OWLObjectSomeValuesFrom) {
						OWLObjectSomeValuesFrom svf = (OWLObjectSomeValuesFrom)op;
						if (svf.getFiller().isAnonymous()) {
							return null;
						}
						OWLClass c = (OWLClass)svf.getFiller();
						// TODO
						String p = graph.getLabel(svf.getProperty());
						if (p == null)
							p = graph.getIdentifier(svf.getProperty());
						else
							p = p.replaceAll(" ","_");
						String y = graph.getIdentifier(c);
						String id = p + "(" + y + ")";
						exts.add(new ExtensionExpression(id, p, y));
					}
					else {
						return null;
					}
				}
			}
		}
		if (exts.size() > 0) {

			LOG.info("UNFOLD: "+ann.getCls()+" -> " +
					genus+" exts: "+exts);


			GeneAnnotation newAnn = new GeneAnnotation(ann);
			newAnn.setExtensionExpressionList(exts);
			if (isReplaceGenus && genus != null) {
				newAnn.setCls(graph.getIdentifier(genus));
			}
			newAnns.add(newAnn);

		}
		return newAnns;

	}

	private OWLClassExpression unfold(OWLOntology ont, OWLClass cls) throws MultipleUnfoldOptionsException {
		OWLClassExpression rx = null;
		for (OWLClassExpression x : cls.getEquivalentClasses(ont)) {
			if (x instanceof OWLClass) {
				continue;
			}
			else {
				if (rx != null) {
					throw new MultipleUnfoldOptionsException(x, rx);
				}	
				rx = x;
			}
		}
		return rx;
	}




	class MultipleUnfoldOptionsException extends Exception {

		public MultipleUnfoldOptionsException(OWLClassExpression x, OWLClassExpression rx) {
			super("multiple unfolds: "+x +" vs "+rx);
		}

	}

}
