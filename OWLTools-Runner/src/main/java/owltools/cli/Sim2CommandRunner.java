package owltools.cli;

import java.io.BufferedOutputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassAssertionAxiom;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyStorageException;

import owltools.cli.tools.CLIMethod;
import owltools.graph.OWLGraphWrapper;
import owltools.io.OWLPrettyPrinter;
import owltools.sim2.SimpleOwlSim;
import owltools.sim2.SimpleOwlSim.Direction;
import owltools.sim2.SimpleOwlSim.EnrichmentConfig;
import owltools.sim2.SimpleOwlSim.EnrichmentResult;
import owltools.sim2.SimpleOwlSim.Metric;
import owltools.sim2.SimpleOwlSim.ScoreAttributePair;
import owltools.sim2.SimpleOwlSim.ScoreAttributesPair;
import owltools.sim2.SimpleOwlSim.SimConfigurationProperty;
import owltools.sim2.preprocessor.AbstractSimPreProcessor;
import owltools.sim2.preprocessor.NullSimPreProcessor;
import owltools.sim2.preprocessor.PhenoSimHQEPreProcessor;
import owltools.sim2.preprocessor.PropertyViewSimPreProcessor;
import owltools.sim2.preprocessor.SimPreProcessor;

/**
 * Semantic similarity and information content.
 * 
 * @author cjm
 *
 */
public class Sim2CommandRunner extends SimCommandRunner {

	private static final Logger LOG = Logger.getLogger(Sim2CommandRunner.class);

	private OWLOntology simOnt = null;
	private String defaultPropertiesFile = "default-sim.properties";
	SimpleOwlSim sos;
	SimPreProcessor pproc;
	Properties simProperties = null;
	int numberOfPairsFiltered = 0;
	protected PrintStream resultOutStream = System.out;

	private void initProperties() {
		simProperties = new Properties();
    //TODO: load a default properties file
		simProperties.setProperty(SimConfigurationProperty.minimumMaxIC.toString(), SimConfigurationProperty.minimumMaxIC.defaultValue());
		simProperties.setProperty(SimConfigurationProperty.minimumSimJ.toString(), SimConfigurationProperty.minimumSimJ.defaultValue());

    /*by default, turn off metrics*/		
//		String[] metrics = SimConfigurationProperty.scoringMetrics.toString().split(",");
//		for (int i=0; i<Metric.values().length; i++) {
//    	simProperties.setProperty(Metric.values()[i].toString(),"0");
//    }    
		simProperties.setProperty(SimConfigurationProperty.scoringMetrics.toString(),SimConfigurationProperty.scoringMetrics.defaultValue());    
	}

	public String getProperty(SimConfigurationProperty p) {
		if (simProperties == null) {
			initProperties();
		}
		return simProperties.getProperty(p.toString());
	}

	private Double getPropertyAsDouble(SimConfigurationProperty p) {
		String v = getProperty(p);
		return Double.valueOf(v);
	}



	@CLIMethod("--save-sim")
	public void saveSim(Opts opts) throws Exception {
		opts.info("FILE", "saves similarity results as an OWL ontology. Use after --sim or --sim-all");
		pw.saveOWL(simOnt, opts.nextOpt(),g);
	}

	@CLIMethod("--merge-sim")
	public void mergeSim(Opts opts) throws Exception {
		g.mergeOntology(simOnt);
	}


	@CLIMethod("--remove-dangling-annotations")
	public void removeDangningAnnotations(Opts opts) throws Exception {
		OWLOntology ont = g.getSourceOntology();
		int n=0;
		Set<OWLAxiom> rmAxioms = new HashSet<OWLAxiom>();
		for (OWLNamedIndividual i : ont.getIndividualsInSignature()) {
			for (OWLClassAssertionAxiom ca : ont.getClassAssertionAxioms(i)) {
				OWLClassExpression cx = ca.getClassExpression();
				if (cx instanceof OWLClass) {
					OWLClass c = (OWLClass)cx;
					String label = g.getLabel(c);
					if (label == null)
						rmAxioms.add(ca);
					else
						n++;
				}
			}
		}
		LOG.info("Removing "+rmAxioms.size()+" axioms");
		ont.getOWLOntologyManager().removeAxioms(ont, rmAxioms);
		LOG.info("Remaining: "+n+" axioms");
	}

	public void attributeAllByAll(Opts opts) {
		sos.setSimProperties(simProperties);
		Set<OWLClass> atts = sos.getAllAttributeClasses();
		LOG.info("All by all for "+atts.size()+" classes");
		//print a header in the file that details what was done
		for (Object k : simProperties.keySet()){
			resultOutStream.print("# "+k+" = "+simProperties.getProperty(k.toString()));
		}
		for (OWLClass i : atts) {
			for (OWLClass j : atts) {
				showSim(i,j);
			}
		}
		LOG.info("FINISHED All by all for "+atts.size()+" classes");
		//LOG.info("Number of pairs filtered as they scored beneath threshold: "+this.numberOfPairsFiltered);
		if (!resultOutStream.equals(System.out))
			resultOutStream.close();
	}



	/**
	 * performs all by all individual comparison
	 * @param opts 
	 */
	public void runOwlSim(Opts opts) {
		sos.setSimProperties(simProperties);
		OWLPrettyPrinter owlpp = getPrettyPrinter();
		if (opts.nextEq("-q")) {
			runOwlSimOnQuery(opts, opts.nextOpt());
			return;
		}
		Set<OWLNamedIndividual> insts = pproc.getOutputOntology().getIndividualsInSignature();
		LOG.info("All by all for "+insts.size()+" individuals");

		//print a header in the file that details what was done
    resultOutStream.println("# Properties for this run:");
		for (Object k : simProperties.keySet()){
      resultOutStream.println("# "+k+" = "+simProperties.getProperty(k.toString()));
      //TODO: output if the property is default
		}

		for (OWLNamedIndividual i : insts) {
			for (OWLNamedIndividual j : insts) {
				// similarity is symmetrical
				if (isComparable(i,j)) {
					showSim(i,j, owlpp);
				}
				else {
					LOG.info("skipping "+i+" + "+j);
				}
			}
		}
		LOG.info("FINISHED All by all for "+insts.size()+" individuals");
		LOG.info("Number of pairs filtered as they scored beneath threshold: "+this.numberOfPairsFiltered);
		resultOutStream.close();
	}

	private void runOwlSimOnQuery(Opts opts, String q) {
		System.out.println("Query: "+q);
		OWLNamedIndividual qi = (OWLNamedIndividual) resolveEntity(q);
		System.out.println("Query Individual: "+qi);
		Set<OWLNamedIndividual> insts = pproc.getOutputOntology().getIndividualsInSignature();
		OWLPrettyPrinter owlpp = getPrettyPrinter();
		for (OWLNamedIndividual j : insts) {
			showSim(qi,j, owlpp);
		}

	}

	private boolean isComparable(OWLNamedIndividual i, OWLNamedIndividual j) {
		String cmp = getProperty(SimConfigurationProperty.compare);
		if (cmp == null) {
			return i.compareTo(j) > 0;
		}
		else {
			String[] idspaces = cmp.split(",");
			if (i.getIRI().toString().contains("/"+idspaces[0]+"_") &&
					j.getIRI().toString().contains("/"+idspaces[1]+"_")) {
				return true;
			}
			else {
				return false;
			}
		}
	}

	boolean isHeaderLine = true;
	/**
	 * Write out attribute x attribute (class x class) similarity
	 * @param a
	 * @param b
	 */
	private void showSim(OWLClass a, OWLClass b) {
		List<String> vals = new ArrayList<String>();
		List<String> cols = new ArrayList<String>();
		// elements
		cols.add("A_ID");
		vals.add(a.toString());
		cols.add("A_Label");
		vals.add(this.g.getLabel(a));
		cols.add("B_ID");
		vals.add(b.toString());
		cols.add("B_Label");
		vals.add(this.g.getLabel(b));

		//scores
		cols.add("SimJ_Score");
		Double simj = sos.getAttributeJaccardSimilarity(a, b);
		if ( simj < getPropertyAsDouble(SimConfigurationProperty.minimumSimJ)) {
			numberOfPairsFiltered ++;
			return;
		}

		vals.add(simj.toString());
		
		cols.add("AsymSimJ_Score");
		Double asimj = sos.getAsymmerticAttributeJaccardSimilarity(a, b);
		vals.add(asimj.toString());
		
		cols.add("LCS_Score");
		cols.add("LCS");
		cols.add("LCS_Label");
		ScoreAttributePair lcs = sos.getLowestCommonSubsumerIC(a, b);
		if ( lcs.score < getPropertyAsDouble(SimConfigurationProperty.minimumMaxIC)) {
			numberOfPairsFiltered ++;
			return;
		}
		vals.add(lcs.score.toString());
		vals.add(lcs.attributeClass.toString());
		vals.add(g.getLabel(lcs.attributeClass));

		if (isHeaderLine) {
			resultOutStream.println(StringUtils.join(cols, "\t"));
			isHeaderLine = false;
		}
		resultOutStream.println(StringUtils.join(vals, "\t"));
		//resultOutStream.println(a+"\t"+b+"\t"+simj+"\t"+lcs.score+"\t"+lcs.attributeClass);
	}

	private void showSim(OWLNamedIndividual i, OWLNamedIndividual j, OWLPrettyPrinter owlpp) {

		int ni = sos.getAttributesForElement(i).size();
		int nj = sos.getAttributesForElement(j).size();

		String[] metrics = getProperty(SimConfigurationProperty.scoringMetrics).split(",");
		ScoreAttributesPair maxic = sos.getSimilarityMaxIC(i, j);
		if ( maxic.score < getPropertyAsDouble(SimConfigurationProperty.minimumMaxIC)) {
			numberOfPairsFiltered ++;
			return;
		}
		float s = sos.getElementJaccardSimilarity(i, j);
		if (s < getPropertyAsDouble(SimConfigurationProperty.minimumSimJ)) {
			numberOfPairsFiltered ++;
			return;
		}

		resultOutStream.println("NumAnnots\t"+renderPair(i,j, owlpp)+"\t"+ni+"\t"+nj+"\t"+(ni+nj)/2);

		for (int m=0; m<metrics.length; m++) {
			  if (metrics[m].equals(Metric.IC_MCS.toString())) {
					ScoreAttributesPair bmaAsymIC = sos.getSimilarityBestMatchAverage(i, j, Metric.IC_MCS, Direction.A_TO_B);
					resultOutStream.println("BMAasymIC\t"+renderPair(i,j, owlpp)+"\t"+bmaAsymIC.score+"\t"+show(bmaAsymIC.attributeClassSet, owlpp));	
          //TODO: do reciprocal BMA
					ScoreAttributesPair bmaSymIC = sos.getSimilarityBestMatchAverage(i, j, Metric.IC_MCS, Direction.AVERAGE);		
					resultOutStream.println("BMAsymIC\t"+renderPair(i,j, owlpp)+"\t"+bmaSymIC.score+"\t"+show(bmaSymIC.attributeClassSet, owlpp));	
			  } else if (metrics[m].equals(Metric.JACCARD.toString())) {
					ScoreAttributesPair bmaAsymJ = sos.getSimilarityBestMatchAverage(i, j, Metric.JACCARD, Direction.A_TO_B);
					resultOutStream.println("BMAasymJ\t"+renderPair(i,j, owlpp)+"\t"+bmaAsymJ.score+"\t"+show(bmaAsymJ.attributeClassSet, owlpp));	
          //TODO: do reciprocal BMA
					ScoreAttributesPair bmaSymJ = sos.getSimilarityBestMatchAverage(i, j, Metric.JACCARD, Direction.AVERAGE);
					resultOutStream.println("BMAsymJ\t"+renderPair(i,j, owlpp)+"\t"+bmaSymJ.score+"\t"+show(bmaSymJ.attributeClassSet, owlpp));	
			  } else if (metrics[m].equals(Metric.GIC.toString())) {
					resultOutStream.println("SimGIC\t"+renderPair(i,j, owlpp)+"\t"+sos.getElementGraphInformationContentSimilarity(i,j));
			  } else if (metrics[m].equals(Metric.MAXIC.toString())) {
					resultOutStream.println("MaxIC\t"+renderPair(i,j, owlpp)+"\t"+maxic.score+"\t"+show(maxic.attributeClassSet, owlpp));
			  } else if (metrics[m].equals(Metric.SIMJ.toString())) {
					resultOutStream.println("SimJ\t"+renderPair(i,j, owlpp)+"\t"+s);
	      }
	  }
	}

	private String renderPair(OWLNamedIndividual i, OWLNamedIndividual j, OWLPrettyPrinter owlpp) {
		//return i+"\t"+owlpp.render(i)+"\t"+j+"\t"+owlpp.render(j);
		return owlpp.render(i)+"\t"+owlpp.render(j);
	}


	protected void loadProperties(Opts opts) throws IOException {
		while (opts.hasOpts()) {
			if (opts.nextEq("-p|--properties")) {
				loadProperties(opts.nextOpt());
	      //need to check if metrics are valid          	        
			}
			else if (opts.nextEq("--set")) {
				simProperties.setProperty(opts.nextOpt(), opts.nextOpt());
			}
			else if (opts.nextEq("-o")) {
				String file = opts.nextOpt();
				FileOutputStream fos;
				try {
					fos = new FileOutputStream(file);
					this.resultOutStream = new PrintStream(new BufferedOutputStream(fos));
				} catch (FileNotFoundException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
			else {
				break;
			}
		}
		//TODO:perhaps there should be a default simProperties file?
		if (simProperties == null) {
			initProperties();
		}
	}


	private void loadProperties(String fn) throws IOException {
		if (simProperties == null)
			this.initProperties();
		FileInputStream myInputStream = new FileInputStream(fn);
		simProperties.load(myInputStream);        

		try {
			showSimProperties(null);
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		myInputStream.close(); // better in finally block

	}

	@CLIMethod("--show-sim-properties")
	public void showSimProperties(Opts opts) throws Exception {
		for (Object k : simProperties.keySet()){
			System.out.println("# "+k+" = "+simProperties.getProperty(k.toString()));
		}
	}
	@CLIMethod("--set-sim-property")
	public void setSimProperty(Opts opts) throws Exception {
		if (simProperties == null) {
			simProperties = new Properties();
		}
		simProperties.setProperty(opts.nextOpt(), opts.nextOpt());
	}

	@CLIMethod("--phenosim")
	public void phenoSim(Opts opts) throws Exception {
		loadProperties(opts);
		try {
			pproc = new PhenoSimHQEPreProcessor();
			pproc.setSimProperties(simProperties);

			pproc.setInputOntology(g.getSourceOntology());
			pproc.setOutputOntology(g.getSourceOntology());
			pproc.preprocess();
			pproc.getReasoner().flush();
			sos = new SimpleOwlSim(g.getSourceOntology());
			sos.setSimPreProcessor(pproc);
			sos.createElementAttributeMapFromOntology();
			// pproc.saveState("/tmp/phenosim-analysis-ontology.owl");
			runOwlSim(opts);
		}
		catch (Exception e) {
			e.printStackTrace();
		}
		finally {
			LOG.info("clearing up...");
			if (pproc != null) {
				pproc.dispose();
			}
		}

	}

	@CLIMethod("--phenosim-attribute-matrix")
	public void phenoSimAttributeMatrix(Opts opts) throws Exception {
		loadProperties(opts);
		try {
			pproc = new PhenoSimHQEPreProcessor();
			pproc.setSimProperties(simProperties);

			pproc.setInputOntology(g.getSourceOntology());
			pproc.setOutputOntology(g.getSourceOntology());
			pproc.preprocess();
			pproc.getReasoner().flush();
			sos = new SimpleOwlSim(g.getSourceOntology());
			sos.setSimPreProcessor(pproc);
			sos.createElementAttributeMapFromOntology();
			// pproc.saveState("/tmp/phenosim-analysis-ontology.owl");
			attributeAllByAll(opts);
		}
		catch (Exception e) {
			e.printStackTrace();
		}
		finally {
			LOG.info("clearing up...");
			if (pproc != null) {
				pproc.dispose();
			}
		}

	}


	@CLIMethod("--sim-resume")
	public void simResume(Opts opts) throws Exception {
		loadProperties(opts);
		OWLOntology ont = pw.parse("file:///tmp/phenosim-analysis-ontology.owl");
		if (g == null) {
			g =	new OWLGraphWrapper(ont);
		}
		else {
			System.out.println("adding support ont "+ont);
			g.addSupportOntology(ont);
		}
		try {
			pproc = new NullSimPreProcessor();
			pproc.setSimProperties(simProperties);
			pproc.setInputOntology(g.getSourceOntology());
			pproc.setOutputOntology(g.getSourceOntology());
			sos = new SimpleOwlSim(g.getSourceOntology());
			sos.setSimPreProcessor(pproc);
			sos.createElementAttributeMapFromOntology();
			runOwlSim(opts);
		}
		catch (Exception e) {
			e.printStackTrace();
		}
		finally {
			if (pproc != null) {
				pproc.dispose();
			}
		}
	}


	@CLIMethod("--sim-basic")
	public void simBasic(Opts opts) throws Exception {
		// assumes that individuals in abox are of types named classes in tbox
		loadProperties(opts);
		try {
			pproc = new NullSimPreProcessor();
			if (simProperties.containsKey(SimConfigurationProperty.analysisRelation.toString())) {
				pproc = new PropertyViewSimPreProcessor();
				String relId = (String) simProperties.get((SimConfigurationProperty.analysisRelation.toString()));
				OWLObjectProperty rel = g.getOWLObjectPropertyByIdentifier(relId);
				PropertyViewSimPreProcessor xpproc = ((PropertyViewSimPreProcessor)pproc);
				
				LOG.info("View relation = "+rel);
				xpproc.analysisRelation = rel;
			}
			pproc.setSimProperties(simProperties);
			pproc.setInputOntology(g.getSourceOntology());
			pproc.setOutputOntology(g.getSourceOntology());
			sos = new SimpleOwlSim(g.getSourceOntology());
			sos.setSimPreProcessor(pproc);
			pproc.preprocess();
			sos.createElementAttributeMapFromOntology();
			runOwlSim(opts);
		}
		finally {
			pproc.dispose();
		}
	}

	@CLIMethod("--sim-dl-query")
	public void simDlQuery(Opts opts) throws Exception {
		loadProperties(opts);
		try {
			// TODO
			pproc = new NullSimPreProcessor();
			pproc.setInputOntology(g.getSourceOntology());
			pproc.setOutputOntology(g.getSourceOntology());
			sos = new SimpleOwlSim(g.getSourceOntology());
			sos.setSimPreProcessor(pproc);
			sos.createElementAttributeMapFromOntology();
			runOwlSim(opts);
		}
		finally {
			pproc.dispose();
		}
	}



	@CLIMethod("--sim-compare-atts")
	public void simAttMatch(Opts opts) throws Exception {
		// assumes that individuals in abox are of types named classes in tbox
		loadProperties(opts);
		try {
			pproc = new NullSimPreProcessor();
			pproc.setInputOntology(g.getSourceOntology());
			pproc.setOutputOntology(g.getSourceOntology());
			sos = new SimpleOwlSim(g.getSourceOntology());
			sos.setSimPreProcessor(pproc);
			sos.createElementAttributeMapFromOntology();
			this.attributeAllByAll(opts);
		}
		finally {
			pproc.dispose();
		}
	}

	// TODO
	@CLIMethod("--enrichment-analysis")
	public void owlsimEnrichmentAnalysis(Opts opts) throws Exception {
		opts.info("", "performs enrichment on gene set. TODO");
		OWLPrettyPrinter owlpp = getPrettyPrinter();
		if (sos == null) {
			sos = new SimpleOwlSim(g.getSourceOntology());
			sos.createElementAttributeMapFromOntology();
		}
		EnrichmentConfig ec = new EnrichmentConfig();
		while (opts.hasOpts()) {
			if (opts.nextEq("-p")) {
				ec.pValueCorrectedCutoff = Double.parseDouble(opts.nextOpt());				
			}
			else if (opts.nextEq("-i")) {
				ec.attributeInformationContentCutoff = Double.parseDouble(opts.nextOpt());				
			}
			else 
				break;
		}
		sos.setEnrichmentConfig(ec);
		OWLClass rc1 = this.resolveClass(opts.nextOpt());
		OWLClass rc2 = this.resolveClass(opts.nextOpt());
		OWLClass pc = g.getDataFactory().getOWLThing();
		List<EnrichmentResult> results = sos.calculateAllByAllEnrichment(pc, rc1, rc2);
		for (EnrichmentResult result : results) {
			System.out.println(render(result, owlpp));
		}
	}



	// NEW
	@CLIMethod("--all-by-all-enrichment-analysis")
	public void owlsimEnrichmentAnalysisAllByAll(Opts opts) throws Exception {
		opts.info("", "performs all by all enrichment");
		OWLPrettyPrinter owlpp = getPrettyPrinter();
		if (sos == null) {
			sos = new SimpleOwlSim(g.getSourceOntology());
			sos.createElementAttributeMapFromOntology();
		}
		EnrichmentConfig ec = new EnrichmentConfig();
		while (opts.hasOpts()) {
			if (opts.nextEq("-p")) {
				ec.pValueCorrectedCutoff = Double.parseDouble(opts.nextOpt());				
			}
			else if (opts.nextEq("-i")) {
				ec.attributeInformationContentCutoff = Double.parseDouble(opts.nextOpt());				
			}
			else 
				break;
		}
		sos.setEnrichmentConfig(ec);
		OWLClass rc1 = this.resolveClass(opts.nextOpt());
		OWLClass rc2 = this.resolveClass(opts.nextOpt());
		OWLClass pc = g.getDataFactory().getOWLThing();
		List<EnrichmentResult> results = sos.calculateAllByAllEnrichment(pc, rc1, rc2);
		for (EnrichmentResult result : results) {
			System.out.println(render(result, owlpp));
		}
	}

	private String render(EnrichmentResult r, OWLPrettyPrinter owlpp) {
		return owlpp.render(r.sampleSetClass) +"\t"+ owlpp.render(r.enrichedClass)
		+"\t"+ r.pValue +"\t"+ r.pValueCorrected;
	}


	private String show(Set<OWLClassExpression> cset, OWLPrettyPrinter owlpp) {
		StringBuffer sb = new StringBuffer();
		for (OWLClassExpression c : cset) {
			sb.append(owlpp.render(c) + "\t");
		}
		return sb.toString();
	}


}
