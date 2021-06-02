package org.dashjoin.service;

import static com.google.common.collect.ImmutableMap.of;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.SecurityContext;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.dashjoin.function.AbstractConfigurableFunction;
import org.dashjoin.function.Function;
import org.dashjoin.model.AbstractDatabase;
import org.dashjoin.model.AbstractDatabase.CreateBatch;
import org.dashjoin.model.Property;
import org.dashjoin.model.Table;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.jboss.resteasy.plugins.providers.multipart.InputPart;
import org.jboss.resteasy.plugins.providers.multipart.MultipartFormDataInput;
import org.sqlite.JDBC;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ibm.db2.jcc.DB2Driver;

/**
 * REST API for management tasks
 */
@Path(Services.REST_PREFIX + "manage")
@Produces({MediaType.APPLICATION_JSON})
@Consumes({MediaType.APPLICATION_JSON})
public class Manage {

  @Inject
  Services services;

  @Inject
  Data data;

  /**
   * describes a column when client calls detect
   */
  public static class TypeSample {
    /**
     * column name
     */
    public String name;

    /**
     * column type (string, integer, number, date, string)
     */
    public String type;

    /**
     * first 10 values
     */
    public List<Object> sample;

    /**
     * suggested as a PK
     */
    public boolean pk;
  }

  /**
   * describes a set of tables to be uploaded
   */
  public static class DetectResult {

    /**
     * cannot be null, true: tables will be created, false: tables can be appended / replaced
     */
    public Boolean createMode;

    /**
     * map: tablename - tableinfo which is List of column infos
     */
    public Map<String, List<TypeSample>> schema = new LinkedHashMap<>();;
  }

  /**
   * allow iterable and index access to a csv record
   */
  static class CSVRecordWrapper extends AbstractList<String> {

    CSVRecord record;

    CSVRecordWrapper(CSVRecord record) {
      this.record = record;
    }

    @Override
    public String get(int index) {
      return record.get(index);
    }

    @Override
    public int size() {
      return record.size();
    }
  }

  /**
   * allow iterable and index access to a excel row
   */
  static class RowWrapper extends AbstractList<String> {

    List<String> record = new ArrayList<>();

    RowWrapper(Row record) {
      for (Cell c : record)
        this.record.add("" + c);
    }

    @Override
    public String get(int index) {
      return record.get(index);
    }

    @Override
    public int size() {
      return record.size();
    }
  }

  protected static final ObjectMapper objectMapper = new ObjectMapper();

  @Inject
  UserProfileManager userProfileManager;

  /**
   * creates the tables contained in input in the database
   */
  @SuppressWarnings("unchecked")
  @POST
  @Path("/create")
  @Consumes("multipart/form-data")
  @Operation(summary = "Create tables and insert data")
  public void create(@Context SecurityContext sc,
      @Parameter(description = "database name to run the operation on",
          example = "northwind") @QueryParam("database") String database,
      MultipartFormDataInput input) throws Exception {

    Map<String, List<InputPart>> uploadForm = input.getFormDataMap();
    InputPart inputPart = uploadForm.get("__dj_schema").get(0);
    List<InputPart> inputParts = uploadForm.get("file");

    InputStream inputStream = inputPart.getBody(InputStream.class, null);
    Map<String, Object> schema = objectMapper.readValue(inputStream, JSONDatabase.tr);

    AbstractDatabase db =
        services.getConfig().getDatabase(services.getDashjoinID() + "/" + database);

    ACLContainerRequestFilter.check(sc, db, null);

    String dbId = services.getDashjoinID() + "/" + database;

    // for every table
    for (Entry<String, Object> entry : schema.entrySet()) {

      // lookup pk / type from metadata
      String pk = null;
      String type = null;
      for (Map<String, Object> prop : (List<Map<String, Object>>) entry.getValue())
        if ((boolean) prop.get("pk")) {
          pk = (String) prop.get("name");
          type = (String) prop.get("type");
        }

      if (pk == null)
        throw new Exception("No primary key defined in table: " + entry.getKey());

      if (type == null)
        throw new Exception("No primary key type defined in table: " + entry.getKey());

      // create table(pk)
      data.create(sc, "config", "Table", new HashMap<>(of("name", entry.getKey(), "parent", dbId,
          "properties", new HashMap<>(of(pk, new HashMap<>(of("type", type)))))));

      // create columns
      for (Map<String, Object> prop : (List<Map<String, Object>>) entry.getValue()) {
        if (prop.get("name").equals(pk))
          continue;
        if (prop.get("type") == null)
          throw new Exception("No type defined for column: " + prop.get("name"));
        data.create(sc, "config", "Property", new HashMap<>(of("name", prop.get("name"), "type",
            prop.get("type"), "parent", dbId + "/" + entry.getKey())));
      }
    }

    // lookup DB again (new metadata)
    db = services.getConfig().getDatabase(services.getDashjoinID() + "/" + database);

    insert(db, inputParts, false);
  }

  /**
   * append data to existing tables in database
   */
  @POST
  @Path("/append")
  @Consumes("multipart/form-data")
  @Operation(summary = "Append data into existing tables")
  public void append(@Context SecurityContext sc,
      @Parameter(description = "database name to run the operation on",
          example = "northwind") @QueryParam("database") String database,
      MultipartFormDataInput input) throws Exception {

    Map<String, List<InputPart>> uploadForm = input.getFormDataMap();
    List<InputPart> inputParts = uploadForm.get("file");

    AbstractDatabase db =
        services.getConfig().getDatabase(services.getDashjoinID() + "/" + database);

    ACLContainerRequestFilter.check(sc, db, null);

    insert(db, inputParts, false);
  }

  void insert(AbstractDatabase db, List<InputPart> inputParts, boolean clearTable)
      throws Exception {
    // for each table
    for (InputPart inputPart : inputParts) {
      MultivaluedMap<String, String> header = inputPart.getHeaders();

      if (getFileExt(header).toLowerCase().equals("csv")) {
        // parse CSV
        InputStream inputStream = inputPart.getBody(InputStream.class, null);
        Reader in = new InputStreamReader(inputStream, StandardCharsets.UTF_8);
        Iterator<CSVRecord> records = CSVFormat.RFC4180.parse(in).iterator();
        CSVRecord headers = records.next();

        // lookup table object
        Table m = db.tables.get(getFileName(header));

        if (clearTable)
          db.delete(m);

        // for each row
        CreateBatch batch = db.openCreateBatch(m);
        while (records.hasNext()) {
          Map<String, Object> object = new HashMap<>();
          int col = 0;
          for (String s : records.next()) {
            object.put(cleanColumnName(headers.get(col)), s);
            col++;
          }
          db.cast(m, object);
          batch.create(object);
        }
        batch.complete();
      } else if (getFileExt(header).toLowerCase().equals("xlsx")) {
        Workbook wb = WorkbookFactory.create(inputPart.getBody(InputStream.class, null));
        for (Sheet sheet : wb) {
          Table m = db.tables.get(sheet.getSheetName());
          if (clearTable)
            db.delete(m);

          Iterator<Row> records = sheet.iterator();
          RowWrapper headers = new RowWrapper(records.next());

          // for each row
          CreateBatch batch = db.openCreateBatch(m);
          while (records.hasNext()) {
            Map<String, Object> object = new HashMap<>();
            int col = 0;
            for (Cell s : records.next()) {
              object.put(cleanColumnName(headers.get(col)), s + "");
              col++;
            }
            db.cast(m, object);
            batch.create(object);
          }
          batch.complete();
        }
      } else if (getFileExt(header).toLowerCase().equals("sqlite")) {
        File tmp = File.createTempFile(getFileName(header), "." + getFileExt(header));
        IOUtils.copy(inputPart.getBody(InputStream.class, null), new FileOutputStream(tmp));
        try (Connection con = DriverManager.getConnection("jdbc:sqlite:" + tmp.getAbsolutePath())) {
          try (ResultSet rs = con.getMetaData().getTables(null, null, null, null)) {
            while (rs.next()) {
              String tablename = rs.getString("TABLE_NAME");
              Table m = db.tables.get(tablename);

              if (clearTable)
                db.delete(m);

              CreateBatch batch = db.openCreateBatch(m);
              try (Statement stmt = con.createStatement()) {
                try (ResultSet rows = stmt.executeQuery("select * from " + tablename)) {
                  ResultSetMetaData md = rows.getMetaData();
                  while (rows.next()) {
                    Map<String, Object> object = new HashMap<>();
                    for (int c = 1; c <= md.getColumnCount(); c++)
                      object.put(md.getColumnName(c), rows.getObject(c));
                    db.cast(m, object);
                    batch.create(object);
                  }
                }
              }
              batch.complete();
            }
          }
        }
        tmp.delete();
      } else
        throw new Exception(
            "Unsupported file type: " + getFileExt(header) + ". Must be csv, xlsx or sqlite.");
    }
  }

  @POST
  @Path("/replace")
  @Consumes("multipart/form-data")
  @Operation(summary = "Delete data in tables and insert new data")
  public void replace(@Context SecurityContext sc,
      @Parameter(description = "database name to run the operation on",
          example = "northwind") @QueryParam("database") String database,
      MultipartFormDataInput input) throws Exception {

    Map<String, List<InputPart>> uploadForm = input.getFormDataMap();
    List<InputPart> inputParts = uploadForm.get("file");

    AbstractDatabase db =
        services.getConfig().getDatabase(services.getDashjoinID() + "/" + database);

    ACLContainerRequestFilter.check(sc, db, null);

    insert(db, inputParts, true);
  }

  @POST
  @Path("/detect")
  @Consumes("multipart/form-data")
  @Operation(summary = "Detect tables, columns and datatypes before uploading", hidden = true)
  public DetectResult detect(@Context SecurityContext sc,
      @Parameter(description = "database name to run the operation on",
          example = "northwind") @QueryParam("database") String database,
      MultipartFormDataInput input) throws Exception {

    DetectResult res = new DetectResult();

    Map<String, List<InputPart>> uploadForm = input.getFormDataMap();
    List<InputPart> inputParts = uploadForm.get("file");

    Database db = services.getConfig().getDatabase(services.getDashjoinID() + "/" + database);

    ACLContainerRequestFilter.check(sc, db, null);

    for (InputPart inputPart : inputParts) {
      MultivaluedMap<String, String> header = inputPart.getHeaders();

      if (getFileExt(header).toLowerCase().equals("csv")) {
        Table m = ((AbstractDatabase) db).tables.get(getFileName(header));
        createMode(res, database, getFileName(header), m);

        // convert the uploaded file to input stream
        InputStream inputStream = inputPart.getBody(InputStream.class, null);

        Reader in = new InputStreamReader(inputStream, StandardCharsets.UTF_8);
        List<CSVRecord> records = CSVFormat.RFC4180.parse(in).getRecords();

        Iterator<CSVRecord> iter = records.iterator();
        CSVRecord first = iter.next();
        List<List<String>> _second = new ArrayList<>();
        for (int i = 0; i < 10; i++)
          _second.add(iter.hasNext() ? new CSVRecordWrapper(iter.next()) : null);

        handleStringTable(res, database, getFileName(header), m, new CSVRecordWrapper(first),
            _second);
      } else if (getFileExt(header).toLowerCase().equals("xlsx")) {
        Workbook wb = WorkbookFactory.create(inputPart.getBody(InputStream.class, null));
        for (Sheet sheet : wb) {
          Table m = ((AbstractDatabase) db).tables.get(sheet.getSheetName());
          createMode(res, database, getFileName(header), m);

          Iterator<Row> iter = sheet.iterator();
          Row first = iter.next();
          List<List<String>> _second = new ArrayList<>();
          for (int i = 0; i < 10; i++)
            _second.add(iter.hasNext() ? new RowWrapper(iter.next()) : null);

          handleStringTable(res, database, sheet.getSheetName(), m, new RowWrapper(first), _second);
        }
      } else if (getFileExt(header).toLowerCase().equals("sqlite")) {
        File tmp = File.createTempFile(getFileName(header), "." + getFileExt(header));
        IOUtils.copy(inputPart.getBody(InputStream.class, null), new FileOutputStream(tmp));
        try (Connection con = DriverManager.getConnection("jdbc:sqlite:" + tmp.getAbsolutePath())) {
          try (ResultSet rs = con.getMetaData().getTables(null, null, null, null)) {
            while (rs.next()) {
              String tablename = rs.getString("TABLE_NAME");
              Table m = ((AbstractDatabase) db).tables.get(tablename);
              createMode(res, database, getFileName(header), m);

              List<String> headers = new ArrayList<>();
              List<List<String>> data = new ArrayList<>();

              try (Statement stmt = con.createStatement()) {
                try (ResultSet rows =
                    stmt.executeQuery("select * from " + tablename + " limit 10")) {
                  ResultSetMetaData md = rows.getMetaData();
                  for (int c = 1; c <= md.getColumnCount(); c++)
                    headers.add(md.getColumnName(c));
                  while (rows.next()) {
                    List<String> row = new ArrayList<>();
                    for (int c = 1; c <= md.getColumnCount(); c++)
                      row.add(rows.getString(c));
                    data.add(row);
                  }
                }
              }

              while (data.size() < 10)
                data.add(null);

              handleStringTable(res, database, tablename, m, headers, data);
            }
          }
        }
      } else
        throw new Exception(
            "Unsupported file type: " + getFileExt(header) + ". Must be csv, xlsx or sqlite.");
    }

    return res;
  }

  void handleStringTable(DetectResult res, String database, String tablename, Table m,
      List<String> first, List<List<String>> _second) throws Exception {
    Map<String, TypeSample> table = new LinkedHashMap<>();
    int col = 0;
    if (res.createMode) {
      boolean pkFound = false;
      for (Object cell : first) {
        TypeSample ts = new TypeSample();
        ts.sample = new ArrayList<>();
        for (List<String> second : _second)
          if (second == null)
            ts.sample.add(null);
          else {
            String value;
            try {
              value = second.get(col);
              if (value != null)
                try {
                  ts.sample.add(Integer.parseInt(value));
                  ts.type = "integer";
                } catch (NumberFormatException e) {
                  try {
                    ts.sample.add(Double.parseDouble(value));
                    ts.type = "number";
                  } catch (NumberFormatException e2) {
                    if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
                      ts.sample.add(Boolean.parseBoolean(value));
                      ts.type = "boolean";
                    } else {
                      ts.sample.add(value);
                      ts.type = "string";
                    }
                  }
                }
            } catch (ArrayIndexOutOfBoundsException e) {
              value = null;
            }
          }

        if (!pkFound) {
          Set<Object> index = new HashSet<>();
          boolean unique = true;
          for (List<String> record : _second)
            if (record != null && !index.add(record.get(col))) {
              unique = false;
              break;
            }
          if (unique) {
            ts.pk = true;
            pkFound = true;
          }
        }

        table.put(cleanColumnName(cell), ts);
        col++;
      }
    } else {
      Set<String> names = new HashSet<>();
      for (Object cell : first)
        names.add(cleanColumnName(cell));

      if (!names.equals(m.properties.keySet())) {
        Set<String> old = new HashSet<>(m.properties.keySet());
        old.removeAll(names);
        names.removeAll(m.properties.keySet());
        throw new Exception("Column names do not match on table " + tablename + ". Remove: " + names
            + ", add: " + old);
      }

      for (Object cell : first) {
        TypeSample ts = new TypeSample();

        Property p = m.properties.get(cleanColumnName(cell));
        ts.pk = p.pkpos == null ? false : p.pkpos == 0;
        ts.type = p.type;

        ts.sample = new ArrayList<>();
        for (List<String> second : _second)
          if (second == null)
            ts.sample.add(null);
          else
            try {
              ts.sample.add(second.get(col));
            } catch (ArrayIndexOutOfBoundsException e) {
              ts.sample.add(null);
            }
        table.put(cleanColumnName(cell), ts);
        col++;
      }

    }
    res.schema.put(tablename, table.entrySet().stream().map(x -> {
      TypeSample v = x.getValue();
      v.name = x.getKey();
      return v;
    }).collect(Collectors.toList()));
  }

  void createMode(DetectResult res, String database, String tablename, Table m) throws Exception {
    if (res.createMode == null)
      if (m == null)
        res.createMode = true;
      else
        res.createMode = false;
    else if (res.createMode)
      if (m == null)
        ;
      else
        throw new Exception("Table " + m.name + " already exists in database " + database);
    else if (m == null)
      throw new Exception("Table " + tablename + " does not exists in database " + database);
    else
      ;
  }

  String cleanColumnName(Object cell) {
    return cell.toString().replace('-', '_');
  }

  String getFileExt(MultivaluedMap<String, String> header) {
    return FilenameUtils.getExtension(getFileNameInternal(header));
  }

  String getFileName(MultivaluedMap<String, String> header) {
    return FilenameUtils.removeExtension(getFileNameInternal(header));
  }

  String getFileNameInternal(MultivaluedMap<String, String> header) {
    String[] contentDisposition = header.getFirst("Content-Disposition").split(";");
    for (String filename : contentDisposition) {
      if ((filename.trim().startsWith("filename"))) {
        String[] name = filename.split("=");
        return URLDecoder.decode(name[1].trim().replaceAll("\"", ""), StandardCharsets.UTF_8);
      }
    }
    throw new RuntimeException("No filename / tablename provided");
  }

  /**
   * returns all JDBC driver classes found on the backend's service loader classpath
   */
  @GET
  @Path("/getDrivers")
  @Operation(
      summary = "returns all JDBC driver classes found on the backend's service loader classpath")
  @APIResponse(description = "List of version objects describing each driver")
  public List<Version> getDrivers() {
    List<Version> res = new ArrayList<>();
    for (Object inst : SafeServiceLoader.load(Driver.class)) {
      Version v = metaInf(inst.getClass(), null, new Version());
      v.name = inst.getClass().getName();
      res.add(v);
    }
    return res;
  }

  /**
   * returns all DB implementation classes found on the backend's service loader classpath
   */
  @GET
  @Path("/getDatabases")
  @Operation(
      summary = "returns all DB implementation classes found on the backend's service loader classpath")
  @APIResponse(description = "List of class names")
  public List<Version> getDatabases() {
    List<Version> res = new ArrayList<>();
    for (Object inst : SafeServiceLoader.load(Database.class)) {
      if (!(inst instanceof PojoDatabase)) {
        Version v = metaInf(inst.getClass(), null, new Version());
        v.name = inst.getClass().getName();
        res.add(v);
      }
    }
    return res;
  }

  /**
   * returns all function implementation classes found on the backend's service loader classpath
   */
  @GET
  @Path("/getFunctions")
  @Operation(
      summary = "returns all action implementation classes found on the backend's service loader classpath")
  @APIResponse(description = "List of class names")
  public List<FunctionVersion> getFunctions() {
    List<FunctionVersion> res = new ArrayList<>();
    for (Function<?, ?> inst : SafeServiceLoader.load(Function.class)) {
      FunctionVersion v = (FunctionVersion) metaInf(inst.getClass(), null, new FunctionVersion());
      v.name = inst.getClass().getName();
      if (inst instanceof AbstractConfigurableFunction)
        v.function = "$call(...)";
      else
        v.function = "$" + inst.getID();
      v.type = inst.getType();
      res.add(v);
    }
    for (String f : new String[] {"$read", "$create", "$update", "$traverse", "$delete", "$query",
        "$call", "$incoming"}) {
      FunctionVersion v = (FunctionVersion) metaInf(getClass(), null, new FunctionVersion());
      v.function = f;
      if (f.equals("$create") || f.equals("$update") || f.equals("$delete"))
        v.type = "write";
      else
        v.type = "read";
      res.add(v);
    }
    return res;
  }

  /**
   * returns all configurable function implementation classes found on the backend's service loader
   * classpath
   */
  @GET
  @Path("/getConfigurableFunctions")
  @Operation(
      summary = "returns all configurable function implementation classes found on the backend's service loader classpath")
  @APIResponse(description = "List of class names")
  public List<FunctionVersion> getConfigurableFunctions() {
    List<FunctionVersion> res = new ArrayList<>();
    for (Function<?, ?> inst : SafeServiceLoader.load(Function.class)) {
      FunctionVersion v = (FunctionVersion) metaInf(inst.getClass(), null, new FunctionVersion());
      v.name = inst.getClass().getName();
      v.type = inst.getType();
      if (inst instanceof AbstractConfigurableFunction) {
        v.function = "$call";
        res.add(v);
      }
    }
    return res;
  }

  /**
   * returns the version of the Dashjoin platform
   */
  @GET
  @Path("/version")
  @Operation(summary = "returns the version of the Dashjoin platform")
  @APIResponse(description = "Version object describing the platform")
  public Version version() {
    Version v = metaInf(getClass(), "dev", new Version());
    v.name = "Dashjoin Low Code Development and Integration Platform";
    v.buildTime = getGitBuildInfo().getProperty("git.build.time", "unknown");
    v.runtime = System.getProperty("java.version");
    return v;
  }

  /**
   * returns the roles of the current user
   * 
   * @throws Exception
   */
  @GET
  @Path("/roles")
  @Operation(summary = "returns the roles of the current user")
  @APIResponse(description = "list of roles")
  public List<String> roles(@Context SecurityContext sc) throws Exception {
    List<String> res = new ArrayList<>();
    for (Map<String, Object> i : services.getConfig().getConfigDatabase()
        .all(Table.ofName("dj-role"), null, null, null, false, null))
      if (sc.isUserInRole((String) i.get("ID")))
        res.add((String) i.get("ID"));
    return res;
  }

  /**
   * holds the version and vendor info from the jar's manifest
   */
  @Schema(title = "Version: holds the version and vendor info from the jar's manifest")
  public static class Version {

    @Schema(title = "Semantic version string")
    public String version;

    @Schema(title = "Software / library name")
    public String title;

    @Schema(title = "Vendor company name")
    public String vendor;

    @Schema(title = "Software / library package name")
    public String name;

    @Schema(title = "Build time")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String buildTime;

    @Schema(title = "Runtime")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String runtime;
  }

  public static class FunctionVersion extends Version {

    @Schema(title = "Function name")
    public String function;

    @Schema(title = "Read only or with side effects")
    public String type;
  }

  Version metaInf(Class<?> c, String def, Version v) {
    v.version = c.getPackage().getImplementationVersion();
    v.title = c.getPackage().getImplementationTitle();
    v.vendor = c.getPackage().getImplementationVendor();

    String jar = null;
    if (c.equals(org.mariadb.jdbc.Driver.class))
      jar = "mariadb";
    if (c.equals(DB2Driver.class))
      jar = "db2";
    if (c.equals(JDBC.class))
      jar = "sqlite";

    if (jar != null)
      try {
        Enumeration<URL> resources =
            getClass().getClassLoader().getResources("META-INF/MANIFEST.MF");
        while (resources.hasMoreElements()) {
          URL next = resources.nextElement();
          if (next.toString().contains(jar)) {

            Properties p = new Properties();
            p.load(next.openStream());
            if (v.version == null)
              v.version = p.getProperty("Bundle-Version");
            if (v.title == null)
              v.title = p.getProperty("Bundle-Name");
          }
        }
      } catch (IOException ignore) {
      }

    if (v.version == null)
      v.version = def;
    if (v.title == null)
      v.title = def;
    if (v.vendor == null)
      v.vendor = def;
    return v;
  }

  /**
   * Tries to read the git.properties which is written by the release build
   * 
   * @return Git build properties
   */
  Properties getGitBuildInfo() {
    String name = "git.properties";
    Properties props = new Properties();
    try {
      props.load(this.getClass().getClassLoader().getResourceAsStream(name));
    } catch (IOException e) {
      // intentionally ignored
    }
    return props;
  }

}