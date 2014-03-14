package owltools.gaf.parser;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import org.apache.commons.io.IOUtils;

import owltools.gaf.Bioentity;
import owltools.gaf.ExtensionExpression;
import owltools.gaf.GafDocument;
import owltools.gaf.GeneAnnotation;

public class GpadGpiObjectsBuilder {

	// list of filters
	private List<LineFilter<GpadParser>> gpadFilters = null;
	private List<LineFilter<GpiParser>> gpiFilters = null;
	private AspectProvider aspectProvider = null;
	
	public void addGpadFilter(LineFilter<GpadParser> filter) {
		if (gpadFilters == null) {
			gpadFilters = new ArrayList<LineFilter<GpadParser>>();
		}
		gpadFilters.add(filter);
	}
	
	public void addGpiFilter(LineFilter<GpiParser> filter) {
		if (gpiFilters == null) {
			gpiFilters = new ArrayList<LineFilter<GpiParser>>();
		}
		gpiFilters.add(filter);
	}
	
	public GafDocument loadGpadGpi(File gpad, File gpi) throws IOException {
		// 1. load GPI
		Map<String, Bioentity> bioentities = loadGPI(getInputStream(gpi));
		
		// create annotation document with bioentities
		String id = gpad.getName()+"+"+gpi.getName();
		GafDocument document = new GafDocument(id, null, bioentities);
		
		// 2. load GPAD
		loadGPAD(getInputStream(gpad), document);
		
		return document;
	}
	
	public void setAspectProvider(AspectProvider aspectProvider) {
		this.aspectProvider = aspectProvider;
	}
	
	public static interface AspectProvider {
		
		public String getAspect(String cls);
	}
	
	private InputStream getInputStream(File file) throws IOException {
		InputStream inputStream = new FileInputStream(file);
		String fileName = file.getName().toLowerCase();
		if (fileName.endsWith(".gz")) {
			inputStream = new GZIPInputStream(inputStream);
		}
		return inputStream;
	}
	
	private Map<String, Bioentity> loadGPI(InputStream inputStream) throws IOException {
		GpiParser parser = null;
		try {
			parser = new GpiParser();
			parser.createReader(inputStream);
			return loadBioentities(parser);
		}
		finally {
			IOUtils.closeQuietly(parser);
		}
	}
	
	private Map<String, Bioentity> loadBioentities(GpiParser parser) throws IOException {
		Map<String, Bioentity> entities = new HashMap<String, Bioentity>();
		
		while(parser.next()) {
			// by default load everything
			boolean load = true;
			if (gpiFilters != null) {
				// check each filter
				for (LineFilter<GpiParser> filter : gpiFilters) {
					boolean accept = filter.accept(parser.getCurrentRow(), parser.getLineNumber(), parser);
					if (accept == false) {
						load = false;
						break;
					}
				}
			}
			if (load) {
				String namespace = parser.getNamespace();
				if (namespace != null) {
					Bioentity bioentity = parseBioentity(parser);
					entities.put(bioentity.getId(), bioentity);
				}
			}
		}
		return entities;
	}
	
	private void loadGPAD(InputStream inputStream, GafDocument document) throws IOException {
		GpadParser parser = null;
		try {
			parser = new GpadParser();
			parser.createReader(inputStream);
			loadGeneAnnotations(parser, document);
		}
		finally {
			IOUtils.closeQuietly(parser);
		}
	}
	
	private void loadGeneAnnotations(GpadParser parser, GafDocument document) throws IOException {
		while(parser.next()) {
			// by default load everything
			boolean load = true;
			if (gpiFilters != null) {
				// check each filter
				for (LineFilter<GpadParser> filter : gpadFilters) {
					boolean accept = filter.accept(parser.getCurrentRow(), parser.getLineNumber(), parser);
					if (accept == false) {
						load = false;
						break;
					}
				}
			}
			if (load) {
				parseAnnotation(parser, document, aspectProvider);
			}
		}
	}

	private static Bioentity parseBioentity(GpiParser parser) {
		String db = parser.getNamespace();
		String bioentityId = db + ":" + parser.getDB_Object_ID();
		Bioentity entity = new Bioentity(bioentityId,
				parser.getDB_Object_Symbol(), parser.getDB_Object_Name(),
				parser.getDB_Object_Type(),
				BuilderTools.handleTaxonPrefix(parser.getTaxon()), db);

		BuilderTools.addSynonyms(parser.getDB_Object_Synonym(), entity);
		entity.setParentObjectId(parser.getParent_Object_ID());
		BuilderTools.addXrefs(parser.getDB_Xref(), entity);
		BuilderTools.addProperties(parser.getGene_Product_Properties(), entity);
		return entity;
	}
	
	private static GeneAnnotation parseAnnotation(GpadParser parser, GafDocument document, AspectProvider aspectProvider) {
		GeneAnnotation ga = new GeneAnnotation();
		String cls = parser.getGO_ID();
		
		// col 1-2
		String bioentityId = parser.getDB() + ":" + parser.getDB_Object_ID();
		Bioentity entity = document.getBioentity(bioentityId);
		ga.setBioentity(entity.getId());
		ga.setBioentityObject(entity);

		// col 3
		final String qualifierString = parser.getQualifier();
		List<String> qualifiers = BuilderTools.parseCompositeQualifier(qualifierString);
		String relation = null;
		for (String qualifier : qualifiers) {
			if (qualifier.equals("NOT")) {
				ga.setIsNegated(true);
			}
			else if (qualifier.equals("contributes_to")) {
				ga.setIsContributesTo(true);
			}
			else if (qualifier.equals("integral_to")) {
				ga.setIsIntegralTo(true);
			}
			relation = qualifier;
		}
		
		String aspect = "";
		if (aspectProvider != null) {
			aspect = aspectProvider.getAspect(cls);
			if (aspect != null && relation == null) {
				if (aspect.equals("F")) {
					relation = "enables";
				} else if (aspect.equals("P")) {
					relation = "involved_in";
				} else if (aspect.equals("C")) {
					relation = "part_of";
				}
			}
		}
		
		// col 4
		ga.setCls(cls);
		
		// col 5
		BuilderTools.addXrefs(parser.getDB_Reference(), ga);
		
		// col 6
		// TODO evidence
		
		// col 7 with
		final String withExpression = parser.getWith();
		final Collection<String> withInfos = BuilderTools.parseWithInfo(withExpression);
		ga.setWithInfos(withExpression, withInfos);
		
		// col8
		String interactingTaxon = BuilderTools.handleTaxonPrefix(parser.getInteracting_Taxon_ID());
		ga.setActsOnTaxonId(interactingTaxon);
		
		// col 9
		ga.setLastUpdateDate(parser.getDate());
		
		// col 10
		ga.setAssignedBy(parser.getAssigned_by());
		
		// col 11
		String extensionExpression = parser.getAnnotation_Extension();
		List<List<ExtensionExpression>> extensionExpressionList = BuilderTools.parseExtensionExpression(extensionExpression);
		ga.setExtensionExpressions(extensionExpressionList);
		
		// col 12
		BuilderTools.addProperties(parser.getAnnotation_Properties(), ga);
		
		
		return ga;
	}
}
