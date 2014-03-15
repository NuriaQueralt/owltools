package owltools.gaf.parser;

import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;

public class GpadParser extends AbstractAnnotationFileParser {
	
	private static final Logger LOG = Logger.getLogger(GpadParser.class);

	private static final String COMMENT_PREFIX = "!";
	private static final String VERSION_PREFIX = "gpad-version:";
	private static final double DEFAULT_VERSION = 0.0d;
	private static final int EXPECTED_COLUMNS = 12;
	
	
	public GpadParser() {
		super(DEFAULT_VERSION, COMMENT_PREFIX, "gpad");
	}
	
	static enum GpadColumns {
		
		DB(1, "DB", true),
		DB_Object_ID(2, "DB_Object_ID", true),
		Qualifier(3, "Qualifier", true),
		GO_ID(4, "GO ID", true),
		DB_Reference(5, "DB:Reference(s)", true),
		Evidence_Code(6, "Evidence code", true),
		With(7, "With (or) From", false),
		Interacting_Taxon_ID(8, "Interacting taxon ID", false),
		Date(9, "Date", true),
		Assigned_by(10, "Assigned_by", true),
		Annotation_Extension(11, "Annotation Extension", true),
		Annotation_Properties(12, "Annotation Properties", true);
		
		private final int pos;
		
		private GpadColumns(int pos, String name, boolean required) {
			this.pos = pos;
		}
		
		private int index() {
			return pos - 1;
		}
	}
	
	public String getColumn(GpadColumns col) {
		return currentCols[col.index()];
	}
	
	public String getDB() {
		return currentCols[GpadColumns.DB.index()];
	}

	public String getDB_Object_ID() {
		return currentCols[GpadColumns.DB_Object_ID.index()];
	}

	public String getQualifier() {
		return currentCols[GpadColumns.Qualifier.index()];
	}

	public String getGO_ID() {
		return currentCols[GpadColumns.GO_ID.index()];
	}

	public String getDB_Reference() {
		return currentCols[GpadColumns.DB_Reference.index()];
	}

	public String getEvidence_Code() {
		return currentCols[GpadColumns.Evidence_Code.index()];
	}

	public String getWith() {
		return currentCols[GpadColumns.With.index()];
	}

	public String getInteracting_Taxon_ID() {
		return currentCols[GpadColumns.Interacting_Taxon_ID.index()];
	}

	public String getDate() {
		return currentCols[GpadColumns.Date.index()];
	}

	public String getAssigned_by() {
		return currentCols[GpadColumns.Assigned_by.index()];
	}

	public String getAnnotation_Extension() {
		return currentCols[GpadColumns.Annotation_Extension.index()];
	}

	public String getAnnotation_Properties() {
		return currentCols[GpadColumns.Annotation_Properties.index()];
	}
	
	//----------------------------
	//
	//----------------------------

	@Override
	protected boolean isFormatDeclaration(String line) {
		return line.startsWith(COMMENT_PREFIX+VERSION_PREFIX);
	}
	
	@Override
	protected double parseVersion(String line) {
		String versionString = line.substring(COMMENT_PREFIX.length()+VERSION_PREFIX.length());
		versionString = StringUtils.trimToNull(versionString);
		if (versionString != null) {
			try {
				return Double.parseDouble(versionString);
			} catch (NumberFormatException e) {
				LOG.info("Could not parse version from line: "+line);
			}
		}
		// fallback: return defaultVersion
		return DEFAULT_VERSION;
	}

	@Override
	protected int getExpectedColumnCount() {
		return EXPECTED_COLUMNS;
	}
}
