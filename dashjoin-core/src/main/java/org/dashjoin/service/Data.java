package org.dashjoin.service;

import static org.dashjoin.service.ACLContainerRequestFilter.Operation.CREATE;
import static org.dashjoin.service.ACLContainerRequestFilter.Operation.DELETE;
import static org.dashjoin.service.ACLContainerRequestFilter.Operation.UPDATE;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.NotAuthorizedException;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import org.dashjoin.expression.ExpressionService;
import org.dashjoin.function.AbstractDatabaseTrigger;
import org.dashjoin.function.AbstractFunction;
import org.dashjoin.function.Function;
import org.dashjoin.model.AbstractDatabase;
import org.dashjoin.model.Property;
import org.dashjoin.model.QueryMeta;
import org.dashjoin.model.Table;
import org.dashjoin.util.MapUtil;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import com.google.common.net.UrlEscapers;
import lombok.extern.java.Log;

/**
 * The core REST data and metadata interface that sits on top of all database implementations. It
 * contains 4 parts:
 * 
 * 1) A object CRUD
 * 
 * 2) Metadata about the objects, fields and types
 * 
 * 3) A generic query interface
 * 
 * 4) metadata about the query parameters and results
 */
@Path(Services.REST_PREFIX + "database")
@Produces({MediaType.APPLICATION_JSON})
@Consumes({MediaType.APPLICATION_JSON})
@ApplicationScoped
@Log
public class Data {

  @Inject
  Services services;

  public void setServices(Services services) {
    this.services = services;
  }

  @Inject
  ExpressionService expression;

  public ExpressionService getExpressionService() {
    return expression;
  }

  /**
   * searches all databases
   */
  @GET
  @Path("/search/{search}")
  @Operation(summary = "performs a full text search on all databases")
  @APIResponse(description = "Tabular query result (list of JSON objects)")
  public List<Map<String, Object>> search(@Context SecurityContext sc,
      @PathParam("search") String search, @QueryParam("limit") Integer limit) throws Exception {
    List<Map<String, Object>> res = new ArrayList<Map<String, Object>>();
    for (AbstractDatabase db : services.getConfig().getDatabases()) {
      try {
        if (db instanceof PojoDatabase)
          if (!sc.isUserInRole("admin"))
            // non admin user technically needs to have read access to the config
            // DB (otherwise he could not read any pages or query definitions)
            // however, only admins should be able to search the config DB during development
            continue;
        ACLContainerRequestFilter.check(sc, db, null);

        if (services.getConfig().excludeFromSearch(db))
          continue;
        String searchQuery = services.getConfig().databaseSearchQuery(db);

        List<Map<String, Object>> tmp =
            searchQuery == null ? db.search(search, limit == null ? null : limit - res.size())
                : searchQuery(sc, db, searchQuery, search);
        if (tmp != null)
          res.addAll(tmp);
        if (limit != null && res.size() >= limit)
          return res;
      } catch (Exception e) {
        // ignore exception on a single DB
      }
    }
    return res;
  }

  List<Map<String, Object>> searchQuery(SecurityContext sc, AbstractDatabase db, String searchQuery,
      String search) throws Exception {

    // Special support for a union-ized search table,
    // where all search tables are union-ized in one index table.
    if (searchQuery.startsWith("dj_search"))
      return indexSearchQuery(sc, db, searchQuery, search);

    Map<String, Property> meta =
        this.queryMeta(sc, db.name, searchQuery, MapUtil.of("search", search));
    String table = null;
    String key = null;
    String column = null;
    for (Entry<String, Property> p : meta.entrySet())
      if (p.getValue().pkpos != null) {
        key = p.getKey();
        table = p.getValue().parent.split("/")[2];
      } else {
        column = p.getKey();
      }

    if (table == null || key == null || column == null)
      log.warning(
          "Search configuration issue: table=" + table + " key=" + key + " column=" + column);

    List<Map<String, Object>> res =
        this.query(sc, db.name, searchQuery, MapUtil.of("search", search));
    List<Map<String, Object>> projected = new ArrayList<>();
    for (Map<String, Object> m : res) {
      projected.add(MapUtil.of("url", "/resource/" + db.name + "/" + table + "/" + m.get(key),
          "table", table, "column", column, "match", m.get(column)));
    }
    return projected;
  }

  /**
   * Perform search on search index for multiple/all types
   * 
   * @param sc
   * @param db
   * @param searchQuery
   * @param search
   * @return
   * @throws Exception
   */
  List<Map<String, Object>> indexSearchQuery(SecurityContext sc, AbstractDatabase db,
      String searchQuery, String search) throws Exception {

    Map<String, Property> meta =
        this.queryMeta(sc, db.name, searchQuery, MapUtil.of("search", search));

    // We expect the search index to return these columns:
    //
    // type -> the table of the result
    // id -> the ID of the record in the table
    // match -> the matching text
    String tableKey = "type";
    String key = "id";
    String column = "match";
    // Look if the columns are prefixed with table name
    // i.e. "table.col" instead of col
    for (Entry<String, Property> p : meta.entrySet())
      if (p.getKey().endsWith(".id"))
        key = p.getKey();
      else if (p.getKey().endsWith(".type"))
        tableKey = p.getKey();
    // else if (p.getKey().endsWith(".match"))
    // column = p.getKey();

    List<Map<String, Object>> res =
        this.query(sc, db.name, searchQuery, MapUtil.of("search", search));
    List<Map<String, Object>> projected = new ArrayList<>();
    for (Map<String, Object> m : res) {

      // For the union-ized search table: need to gather table from each record
      Object table = m.get(tableKey);
      if (table != null)
        projected.add(MapUtil.of("url", "/resource/" + db.name + "/" + table + "/" + m.get(key),
            "table", table, "column", column, "match", m.get(column)));
      else
        log.warning("Search result has no type, ignored: " + m);
    }
    return projected;
  }


  /**
   * looks up the query with the given ID in the catalog, finds the right database, inserts the
   * arguments, runs the query and returns the result
   */
  @POST
  @Path("/query/{database}/{queryId}")
  @Operation(
      summary = "looks up the query with the given ID in the catalog, finds the right database, inserts the arguments, runs the query and returns the result")
  @APIResponse(description = "Tabular query result (list of JSON objects)")
  public List<Map<String, Object>> query(@Context SecurityContext sc,
      @Parameter(description = "database name to run the operation on",
          example = "northwind") @PathParam("database") String database,
      @PathParam("queryId") String queryId, Map<String, Object> arguments) throws Exception {
    return queryInternal(sc, database, queryId, arguments, false);
  }

  public List<Map<String, Object>> queryInternal(SecurityContext sc, String database,
      String queryId, Map<String, Object> arguments, boolean readOnly) throws Exception {
    if (arguments == null)
      arguments = new HashMap<>();
    QueryMeta info = services.getConfig().getQueryMeta(queryId);
    if (info == null)
      throw new Exception("Query " + queryId + " not found");

    if (readOnly && "write".equals(info.type))
      return null;

    ACLContainerRequestFilter.check(sc, info);
    Database db = services.getConfig().getDatabase(dj(database));
    return db.query(info, arguments);
  }

  /**
   * looks up the query with the given ID in the catalog, finds the right database, inserts the
   * arguments, and returns the result metadata
   */
  @POST
  @Path("/queryMeta/{database}/{queryId}")
  @Operation(
      summary = "looks up the query with the given ID in the catalog, finds the right database, inserts the arguments, and returns the result metadata")
  @APIResponse(
      description = "Map of column name (as they appear when running the query) to Property describing the column")
  public Map<String, Property> queryMeta(@Context SecurityContext sc,
      @Parameter(description = "database name to run the operation on",
          example = "northwind") @PathParam("database") String database,
      @PathParam("queryId") String queryId, Map<String, Object> arguments) throws Exception {
    if (arguments == null)
      arguments = new HashMap<>();
    QueryMeta info = services.getConfig().getQueryMeta(queryId);
    ACLContainerRequestFilter.check(sc, info);
    Database db = services.getConfig().getDatabase(dj(database));
    return db.queryMeta(info, arguments);
  }

  /**
   * like read but returns all matches in a list (arguments are and-connected column equalities)
   */
  @POST
  @Path("/all/{database}/{table}")
  @Operation(
      summary = "like read but returns all matches in a list (arguments are and-connected column equalities)")
  @APIResponse(description = "Tabular result (list of JSON objects)")
  public List<Map<String, Object>> all(@Context SecurityContext sc,
      @Parameter(description = "database name to run the operation on",
          example = "northwind") @PathParam("database") String database,
      @Parameter(description = "table name to run the operation on",
          example = "EMPLOYEES") @PathParam("table") String table,
      @QueryParam("offset") Integer offset, @QueryParam("limit") Integer limit,
      @QueryParam("sort") String sort, @QueryParam("descending") boolean descending,
      Map<String, Object> arguments) throws Exception {
    AbstractDatabase db = services.getConfig().getDatabase(dj(database));
    Table m = db.tables.get(table);
    ACLContainerRequestFilter.check(sc, db, m);
    db.cast(m, arguments);
    return db.all(m, offset, limit, sort, descending, arguments);
  }

  // "Get all" as GET method. Enables browser cache.
  @GET
  @Path("/crud/{database}/{table}")
  @Operation(
      summary = "like read but returns all matches in a list (arguments are and-connected column equalities)")
  @APIResponse(description = "Tabular result (list of JSON objects)")
  public List<Map<String, Object>> getall(@Context SecurityContext sc,
      @Parameter(description = "database name to run the operation on",
          example = "northwind") @PathParam("database") String database,
      @Parameter(description = "table name to run the operation on",
          example = "EMPLOYEES") @PathParam("table") String table,
      @QueryParam("offset") Integer offset, @QueryParam("limit") Integer limit,
      @QueryParam("sort") String sort, @QueryParam("descending") boolean descending,
      @QueryParam("arguments") String argStr) throws Exception {
    AbstractDatabase db = services.getConfig().getDatabase(dj(database));
    Table m = db.tables.get(table);
    ACLContainerRequestFilter.check(sc, db, m);
    Map<String, Object> arguments = argStr == null ? null : JSONDatabase.fromJsonString(argStr);
    db.cast(m, arguments);
    return db.all(m, offset, limit, sort, descending, arguments);
  }

  /**
   * return limit keys whose string representation begin with prefix
   */
  @GET
  @Path("/keys/{database}/{table}")
  @Operation(summary = "return limit keys whose string representation begin with prefix")
  @APIResponse(description = "key value and string display name (list of JSON objects)")
  public List<Choice> keys(@Context SecurityContext sc,
      @Parameter(description = "database name to run the operation on",
          example = "northwind") @PathParam("database") String database,
      @Parameter(description = "table name to run the operation on",
          example = "EMPLOYEES") @PathParam("table") String table,
      @QueryParam("prefix") String prefix, @QueryParam("limit") Integer limit) throws Exception {
    Database db = services.getConfig().getDatabase(dj(database));
    Table m = ((AbstractDatabase) db).tables.get(table);
    ACLContainerRequestFilter.check(sc, db, m);

    if (m.properties != null) {
      // only makes sense for tables with a single PK
      int count = 0;
      for (Property p : m.properties.values())
        if (p.pkpos != null)
          count++;
      if (count != 1)
        return Arrays.asList();
    }

    return db.keys(m, prefix, limit);
  }

  /**
   * returns all table IDs from all databases known to the system
   */
  @GET
  @Path("/tables")
  @Operation(summary = "returns all table IDs from all databases known to the system")
  @APIResponse(description = "List of table ID")
  public List<String> tables() throws Exception {
    List<String> res = new ArrayList<>();
    for (AbstractDatabase s : services.getConfig().getDatabases())
      for (Table table : s.tables.values())
        if (table.ID != null)
          res.add(table.ID);
    return res;
  }

  /**
   * creates a new instance in the table associated with the table
   */
  @PUT
  @Path("/crud/{database}/{table}")
  @Operation(summary = "creates a new instance in the table associated with the table")
  @APIResponse(
      description = "Returns the global identifier of the new record (dj/database/table/ID). The segments are URL encoded. ID is the primary key. For composite primary keys, ID is pk1/../pkn where pki is URL encoded again.")
  @Produces({MediaType.TEXT_PLAIN})
  public String create(@Context SecurityContext sc,
      @Parameter(description = "database name to run the operation on",
          example = "northwind") @PathParam("database") String database,
      @Parameter(description = "table name to run the operation on",
          example = "EMPLOYEES") @PathParam("table") String table,
      Map<String, Object> object) throws Exception {
    MapUtil.clean(object);
    AbstractDatabase db = services.getConfig().getDatabase(dj(database));
    Table m = db.tables.get(table);
    ACLContainerRequestFilter.check(sc, db, m, CREATE);

    // make sure PKs are present (unless this is a DDL create)
    if (!"config".equals(database))
      if (!("Table".equals(table) || "Property".equals(table)))
        if (m.properties != null)
          for (Property p : m.properties.values())
            if (p.pkpos != null)
              if (!object.containsKey(p.name))
                if (p.readOnly != null && p.readOnly)
                  // the key is an auto increment key, ok to omit it
                  ;
                else
                  throw new Exception("Record is missing the primary key column " + p.name);

    db.cast(m, object);

    if (dbTriggers(sc, "create", database, table, null, object, m.beforeCreate))
      db.create(m, object);
    dbTriggers(sc, "create", database, table, null, object, m.afterCreate);
    return key(database, m, object);
  }

  boolean dbTriggers(SecurityContext sc, String command, String database, String table,
      Map<String, Object> search, Map<String, Object> object, String t) throws Exception {
    if (t == null)
      // no trigger, continue
      return true;

    Function<?, ?> f = null;
    for (Function<?, ?> s : SafeServiceLoader.load(Function.class)) {
      if (s instanceof AbstractDatabaseTrigger)
        if (t.equals("$" + s.getID() + "()"))
          f = s;
    }

    if (f != null) {
      AbstractDatabaseTrigger.Config context = new AbstractDatabaseTrigger.Config();
      context.command = command;
      context.database = database;
      context.table = table;
      context.search = search;
      context.object = object;
      ((AbstractFunction<?, ?>) f).init(sc, services, expression, false);
      ACLContainerRequestFilter.check(sc, f);
      return ((AbstractDatabaseTrigger) f).run(context);
    }

    Map<String, Object> context = new HashMap<>();
    context.put("command", command);
    context.put("database", database);
    context.put("table", table);
    context.put("search", search);
    context.put("object", object);
    expression.resolve(sc, t, context);
    return true;
  }

  @GET
  @Path("/traverse/{database}/{table}/{objectId1}")
  @Operation(
      summary = "starting at the object defined by the given globally unique identifier, reads the object related via fk")
  @APIResponse(description = "JSON object representing the record")
  public Object traverse(@Context SecurityContext sc,
      @Parameter(description = "database name to run the operation on",
          example = "northwind") @PathParam("database") String database,
      @Parameter(description = "table name to run the operation on",
          example = "EMPLOYEES") @PathParam("table") String table,
      @Parameter(description = "Primary key of the record to operate on",
          example = "1") @PathParam("objectId1") String objectId1,
      @QueryParam("fk") String fk) throws Exception {
    Map<String, Object> o = read(sc, database, table, Arrays.asList(objectId1));
    AbstractDatabase db = services.getConfig().getDatabase(dj(database));
    Table m = db.tables.get(table);
    Property p = m.properties.get(fk);
    if (p != null && p.ref != null) {
      // fk is an outgoing fk
      String[] parts = m.properties.get(fk).ref.split("/");
      return read(sc, parts[1], parts[2], "" + o.get(fk));
    } else {
      String[] parts = fk.split("/");
      AbstractDatabase db2 = services.getConfig().getDatabase(dj(parts[1]));
      Table m2 = db2.tables.get(parts[2]);
      // get all of the related table where fk = pk
      return all(sc, parts[1], parts[2], null, null, null, false,
          MapUtil.of(fk(m2, pk(m).ID).name, o.get(pk(m).name)));
    }
  }

  Property pk(Table t) {
    for (Property p : t.properties.values())
      if (p.pkpos != null)
        return p;
    return null;
  }

  Property fk(Table t, String ref) {
    for (Property p : t.properties.values())
      if (ref.equals(p.ref))
        return p;
    return null;
  }

  /**
   * reads the object defined by the given globally unique identifier
   */
  @GET
  @Path("/crud/{database}/{table}/{objectId1}")
  @Operation(summary = "reads the object defined by the given globally unique identifier")
  @APIResponse(description = "JSON object representing the record")
  public Map<String, Object> read(@Context SecurityContext sc,
      @Parameter(description = "database name to run the operation on",
          example = "northwind") @PathParam("database") String database,
      @Parameter(description = "table name to run the operation on",
          example = "EMPLOYEES") @PathParam("table") String table,
      @Parameter(description = "Primary key of the record to operate on",
          example = "1") @PathParam("objectId1") String objectId1)
      throws Exception {
    return read(sc, database, table, Arrays.asList(objectId1));
  }

  @GET
  @Path("/crud/{database}/{table}/{objectId1}/{objectId2}")
  @Operation(summary = "reads the object defined by the given globally unique identifier")
  @APIResponse(description = "JSON object representing the record")
  public Map<String, Object> read(@Context SecurityContext sc,
      @Parameter(description = "database name to run the operation on",
          example = "northwind") @PathParam("database") String database,
      @Parameter(description = "table name to run the operation on",
          example = "EMPLOYEES") @PathParam("table") String table,
      @Parameter(description = "Composite primary key 1 of the record to operate on",
          example = "1") @PathParam("objectId1") String objectId1,
      @Parameter(description = "Composite primary key 2 of the record to operate on",
          example = "1") @PathParam("objectId2") String objectId2)
      throws Exception {
    return read(sc, database, table, Arrays.asList(objectId1, objectId2));
  }

  @GET
  @Path("/crud/{database}/{table}/{objectId1}/{objectId2}/{objectId3}")
  @Operation(summary = "reads the object defined by the given globally unique identifier")
  @APIResponse(description = "JSON object representing the record")
  public Map<String, Object> read(@Context SecurityContext sc,
      @Parameter(description = "database name to run the operation on",
          example = "northwind") @PathParam("database") String database,
      @Parameter(description = "table name to run the operation on",
          example = "EMPLOYEES") @PathParam("table") String table,
      @Parameter(description = "Composite primary key 1 of the record to operate on",
          example = "1") @PathParam("objectId1") String objectId1,
      @Parameter(description = "Composite primary key 2 of the record to operate on",
          example = "1") @PathParam("objectId2") String objectId2,
      @Parameter(description = "Composite primary key 3 of the record to operate on",
          example = "1") @PathParam("objectId3") String objectId3)
      throws Exception {
    return read(sc, database, table, Arrays.asList(objectId1, objectId2, objectId3));
  }

  @GET
  @Path("/crud/{database}/{table}/{objectId1}/{objectId2}/{objectId3}/{objectId4}")
  @Operation(summary = "reads the object defined by the given globally unique identifier")
  @APIResponse(description = "JSON object representing the record")
  public Map<String, Object> read(@Context SecurityContext sc,
      @Parameter(description = "database name to run the operation on",
          example = "northwind") @PathParam("database") String database,
      @Parameter(description = "table name to run the operation on",
          example = "EMPLOYEES") @PathParam("table") String table,
      @Parameter(description = "Composite primary key 1 of the record to operate on",
          example = "1") @PathParam("objectId1") String objectId1,
      @Parameter(description = "Composite primary key 2 of the record to operate on",
          example = "1") @PathParam("objectId2") String objectId2,
      @Parameter(description = "Composite primary key 3 of the record to operate on",
          example = "1") @PathParam("objectId3") String objectId3,
      @Parameter(description = "Composite primary key 4 of the record to operate on",
          example = "1") @PathParam("objectId4") String objectId4)
      throws Exception {
    return read(sc, database, table, Arrays.asList(objectId1, objectId2, objectId3, objectId4));
  }

  public Map<String, Object> read(@Context SecurityContext sc, String database, String table,
      List<String> objectId) throws Exception {
    AbstractDatabase db = services.getConfig().getDatabase(dj(database));
    Table m = db.tables.get(table);
    ACLContainerRequestFilter.check(sc, db, m);
    Map<String, Object> search = key(m, objectId);
    db.cast(m, search);
    Map<String, Object> res = db.read(m, search);
    if (res == null)
      if ("config".equals(database) && "namespace".equals(search.get("ID")))
        // avoid 401 for namespace query so apps can still boostrap namespace.json
        return MapUtil.of("map", MapUtil.of());
      else
        throw new NotFoundException();
    return res;
  }

  /**
   * represents an incoming (fk) link to an object
   */
  @Schema(title = "Origin: represents an incoming (fk) link to an object")
  public static class Origin {

    @Schema(title = "ID of the record where the link originates")
    public String id;

    @Schema(title = "ID of the pk column")
    public String pk;

    @Schema(title = "ID of the fk column")
    public String fk;
  }

  /**
   * choice for helping the user pick a correct foreign key
   */
  @Schema(title = "Choice: represents a foreign key choice for select UIs")
  public static class Choice {

    @Schema(title = "string representation")
    public String name;

    @Schema(title = "key value")
    public Object value;

  }

  /**
   * returns IDs of objects who have a FK pointing to the object defined by the given globally
   * unique identifier
   */
  @GET
  @Path("/incoming/{database}/{table}/{objectId1}")
  @Operation(
      summary = "returns IDs of objects who have a FK pointing to the object defined by the given globally unique identifier")
  @APIResponse(description = "List of incoming links")
  public List<Origin> incoming(@Context SecurityContext sc,
      @Parameter(description = "database name to run the operation on",
          example = "northwind") @PathParam("database") String database,
      @Parameter(description = "table name to run the operation on",
          example = "EMPLOYEES") @PathParam("table") String table,
      @Parameter(description = "Primary key of the record to operate on",
          example = "1") @PathParam("objectId1") String objectId1,
      @QueryParam("offset") Integer offset, @QueryParam("limit") Integer limit) throws Exception {
    return incoming(sc, database, table, Arrays.asList(objectId1), offset, limit);
  }

  @GET
  @Path("/incoming/{database}/{table}/{objectId1}/{objectId2}")
  @Operation(
      summary = "returns IDs of objects who have a FK pointing to the object defined by the given globally unique identifier")
  @APIResponse(description = "List of incoming links")
  public List<Origin> incoming(@Context SecurityContext sc,
      @Parameter(description = "database name to run the operation on",
          example = "northwind") @PathParam("database") String database,
      @Parameter(description = "table name to run the operation on",
          example = "EMPLOYEES") @PathParam("table") String table,
      @Parameter(description = "Composite primary key 1 of the record to operate on",
          example = "1") @PathParam("objectId1") String objectId1,
      @Parameter(description = "Composite primary key 2 of the record to operate on",
          example = "1") @PathParam("objectId2") String objectId2,
      @QueryParam("offset") Integer offset, @QueryParam("limit") Integer limit) throws Exception {
    return incoming(sc, database, table, Arrays.asList(objectId1, objectId2), offset, limit);
  }

  @GET
  @Path("/incoming/{database}/{table}/{objectId1}/{objectId2}/{objectId3}")
  @Operation(
      summary = "returns IDs of objects who have a FK pointing to the object defined by the given globally unique identifier")
  @APIResponse(description = "List of incoming links")
  public List<Origin> incoming(@Context SecurityContext sc,
      @Parameter(description = "database name to run the operation on",
          example = "northwind") @PathParam("database") String database,
      @Parameter(description = "table name to run the operation on",
          example = "EMPLOYEES") @PathParam("table") String table,
      @Parameter(description = "Composite primary key 1 of the record to operate on",
          example = "1") @PathParam("objectId1") String objectId1,
      @Parameter(description = "Composite primary key 2 of the record to operate on",
          example = "1") @PathParam("objectId2") String objectId2,
      @Parameter(description = "Composite primary key 3 of the record to operate on",
          example = "1") @PathParam("objectId3") String objectId3,
      @QueryParam("offset") Integer offset, @QueryParam("limit") Integer limit) throws Exception {
    return incoming(sc, database, table, Arrays.asList(objectId1, objectId2, objectId3), offset,
        limit);
  }

  @GET
  @Path("/incoming/{database}/{table}/{objectId1}/{objectId2}/{objectId3}/{objectId4}")
  @Operation(
      summary = "returns IDs of objects who have a FK pointing to the object defined by the given globally unique identifier")
  @APIResponse(description = "List of incoming links")
  public List<Origin> incoming(@Context SecurityContext sc,
      @Parameter(description = "database name to run the operation on",
          example = "northwind") @PathParam("database") String database,
      @Parameter(description = "table name to run the operation on",
          example = "EMPLOYEES") @PathParam("table") String table,
      @Parameter(description = "Composite primary key 1 of the record to operate on",
          example = "1") @PathParam("objectId1") String objectId1,
      @Parameter(description = "Composite primary key 2 of the record to operate on",
          example = "1") @PathParam("objectId2") String objectId2,
      @Parameter(description = "Composite primary key 3 of the record to operate on",
          example = "1") @PathParam("objectId3") String objectId3,
      @Parameter(description = "Composite primary key 4 of the record to operate on",
          example = "1") @PathParam("objectId4") String objectId4,
      @QueryParam("offset") Integer offset, @QueryParam("limit") Integer limit) throws Exception {
    return incoming(sc, database, table, Arrays.asList(objectId1, objectId2, objectId3, objectId4),
        offset, limit);
  }

  List<Origin> incoming(@Context SecurityContext sc, String database, String table,
      List<String> objectIds, Integer offset, Integer limit) throws Exception {

    long start = System.currentTimeMillis();
    Integer timeout = services.getConfig().getAllTimeoutMs();

    Database db = services.getConfig().getDatabase(dj(database));
    Table m = ((AbstractDatabase) db).tables.get(table);
    ACLContainerRequestFilter.check(sc, db, m);
    String objectId = objectIds.get(0);

    String pk = null;
    for (Property p : m.properties.values())
      if (p.pkpos != null) {
        if (pk != null)
          // incoming for composite key not yet supported
          // https://github.com/dashjoin/query-editor/issues/62
          return Arrays.asList();
        pk = p.ID;
      }

    if (pk == null)
      return Arrays.asList();

    List<Origin> res = new ArrayList<>();
    for (AbstractDatabase d : services.getConfig().getDatabases())
      for (Table s : d.tables.values()) {

        if (s.name == null)
          continue;

        try {
          ACLContainerRequestFilter.check(sc, d, m);
          if (s.properties != null)
            for (Property p : s.properties.values())
              if (pk.equals(p.ref)) {
                Map<String, Object> search = new HashMap<>();
                search.put(p.name, objectId);
                d.cast(s, search);
                for (Map<String, Object> match : d.all(s, offset, limit, null, false, search)) {
                  Origin o = new Origin();
                  o.id = key(d.name, s, match);
                  o.fk = p.ID;
                  o.pk = pk;
                  res.add(o);
                }
                if (timeout != null)
                  if (System.currentTimeMillis() - start > timeout)
                    return res;
              }
        } catch (NotAuthorizedException ignore) {
        }
      }
    return res;
  }

  /**
   * like read, but returns all keys posted
   */
  @POST
  @Path("/list/{database}/{table}")
  @Operation(summary = "like read, but returns all keys posted")
  @APIResponse(description = "Map of objectId to JSON object representing the record")
  public Map<String, Map<String, Object>> list(
      @Parameter(description = "database name to run the operation on",
          example = "northwind") @PathParam("database") String database,
      @Parameter(description = "table name to run the operation on",
          example = "EMPLOYEES") @PathParam("table") String table,
      List<String> objectIds) throws Exception {
    AbstractDatabase db = services.getConfig().getDatabase(dj(database));
    Table m = db.tables.get(table);
    Map<String, Map<String, Object>> res = new HashMap<>();
    for (String objectId : objectIds) {
      Map<String, Object> search = key(m, Arrays.asList(objectId));
      db.cast(m, search);
      res.put(objectId, db.read(m, search));
    }
    return res;
  }

  /**
   * updates the object defined by the globally unique identifier with the values provided in object
   * 
   * @param object object a map of keys that are set to new values. Columns / keys that are omitted
   *        are not touched. If key=null, the key is deleted. All other keys are replaced (no
   *        merging takes place
   */
  @POST
  @Path("/crud/{database}/{table}/{objectId1}")
  @Operation(
      summary = "updates the object defined by the globally unique identifier with the values provided in object")
  public Response update(@Context SecurityContext sc,
      @Parameter(description = "database name to run the operation on",
          example = "northwind") @PathParam("database") String database,
      @Parameter(description = "table name to run the operation on",
          example = "EMPLOYEES") @PathParam("table") String table,
      @Parameter(description = "Primary key of the record to operate on",
          example = "1") @PathParam("objectId1") String objectId1,
      Map<String, Object> object) throws Exception {
    update(sc, database, table, Arrays.asList(objectId1), object);
    java.net.URI uri = new java.net.URI(
        "/rest/database/crud/" + e(database) + "/" + e(table) + "/" + e(objectId1));
    return Response.created(uri).build();
  }

  @POST
  @Path("/crud/{database}/{table}/{objectId1}/{objectId2}")
  @Operation(
      summary = "updates the object defined by the globally unique identifier with the values provided in object")
  public void update(@Context SecurityContext sc,
      @Parameter(description = "database name to run the operation on",
          example = "northwind") @PathParam("database") String database,
      @Parameter(description = "table name to run the operation on",
          example = "EMPLOYEES") @PathParam("table") String table,
      @Parameter(description = "Composite primary key 1 of the record to operate on",
          example = "1") @PathParam("objectId1") String objectId1,
      @Parameter(description = "Composite primary key 2 of the record to operate on",
          example = "1") @PathParam("objectId2") String objectId2,
      Map<String, Object> object) throws Exception {
    update(sc, database, table, Arrays.asList(objectId1, objectId2), object);
  }

  @POST
  @Path("/crud/{database}/{table}/{objectId1}/{objectId2}/{objectId3}")
  @Operation(
      summary = "updates the object defined by the globally unique identifier with the values provided in object")
  public void update(@Context SecurityContext sc,
      @Parameter(description = "database name to run the operation on",
          example = "northwind") @PathParam("database") String database,
      @Parameter(description = "table name to run the operation on",
          example = "EMPLOYEES") @PathParam("table") String table,
      @Parameter(description = "Composite primary key 1 of the record to operate on",
          example = "1") @PathParam("objectId1") String objectId1,
      @Parameter(description = "Composite primary key 2 of the record to operate on",
          example = "1") @PathParam("objectId2") String objectId2,
      @Parameter(description = "Composite primary key 3 of the record to operate on",
          example = "1") @PathParam("objectId3") String objectId3,
      Map<String, Object> object) throws Exception {
    update(sc, database, table, Arrays.asList(objectId1, objectId2, objectId3), object);
  }

  @POST
  @Path("/crud/{database}/{table}/{objectId1}/{objectId2}/{objectId3}/{objectId4}")
  @Operation(
      summary = "updates the object defined by the globally unique identifier with the values provided in object")
  public void update(@Context SecurityContext sc,
      @Parameter(description = "database name to run the operation on",
          example = "northwind") @PathParam("database") String database,
      @Parameter(description = "table name to run the operation on",
          example = "EMPLOYEES") @PathParam("table") String table,
      @Parameter(description = "Composite primary key 1 of the record to operate on",
          example = "1") @PathParam("objectId1") String objectId1,
      @Parameter(description = "Composite primary key 2 of the record to operate on",
          example = "1") @PathParam("objectId2") String objectId2,
      @Parameter(description = "Composite primary key 3 of the record to operate on",
          example = "1") @PathParam("objectId3") String objectId3,
      @Parameter(description = "Composite primary key 4 of the record to operate on",
          example = "1") @PathParam("objectId4") String objectId4,
      Map<String, Object> object) throws Exception {
    update(sc, database, table, Arrays.asList(objectId1, objectId2, objectId3, objectId4), object);
  }

  void update(SecurityContext sc, String database, String table, List<String> objectId,
      Map<String, Object> object) throws Exception {
    MapUtil.clean(object);
    AbstractDatabase db = services.getConfig().getDatabase(dj(database));
    Table m = db.tables.get(table);
    ACLContainerRequestFilter.check(sc, db, m, UPDATE);
    Map<String, Object> search = key(m, objectId);

    // make sure we do not change a PK
    for (Entry<String, Object> e : search.entrySet()) {
      Object pk = object.get(e.getKey());
      if (pk != null)
        if (!pk.toString().equals("" + e.getValue()))
          throw new Exception("Cannot change key: " + e.getKey());
    }

    db.cast(m, search);
    db.cast(m, object);

    if (!dbTriggers(sc, "update", database, table, search, object, m.beforeUpdate))
      return;
    if (!db.update(m, search, object))
      throw new NotFoundException();
    dbTriggers(sc, "update", database, table, search, object, m.afterUpdate);
  }

  /**
   * deletes the object defined by the globally unique identifier
   */
  @DELETE
  @Path("/crud/{database}/{table}/{objectId1}")
  @Operation(summary = "deletes the object defined by the globally unique identifier")
  public void delete(@Context SecurityContext sc,
      @Parameter(description = "database name to run the operation on",
          example = "northwind") @PathParam("database") String database,
      @Parameter(description = "table name to run the operation on",
          example = "EMPLOYEES") @PathParam("table") String table,
      @Parameter(description = "Primary key of the record to operate on",
          example = "1") @PathParam("objectId1") String objectId1)
      throws Exception {
    delete(sc, database, table, Arrays.asList(objectId1));
  }

  @DELETE
  @Path("/crud/{database}/{table}/{objectId1}/{objectId2}")
  @Operation(summary = "deletes the object defined by the globally unique identifier")
  public void delete(@Context SecurityContext sc,
      @Parameter(description = "database name to run the operation on",
          example = "northwind") @PathParam("database") String database,
      @Parameter(description = "table name to run the operation on",
          example = "EMPLOYEES") @PathParam("table") String table,
      @Parameter(description = "Composite primary key 1 of the record to operate on",
          example = "1") @PathParam("objectId1") String objectId1,
      @Parameter(description = "Composite primary key 2 of the record to operate on",
          example = "1") @PathParam("objectId2") String objectId2)
      throws Exception {
    delete(sc, database, table, Arrays.asList(objectId1, objectId2));
  }

  @DELETE
  @Path("/crud/{database}/{table}/{objectId1}/{objectId2}/{objectId3}")
  @Operation(summary = "deletes the object defined by the globally unique identifier")
  public void delete(@Context SecurityContext sc,
      @Parameter(description = "database name to run the operation on",
          example = "northwind") @PathParam("database") String database,
      @Parameter(description = "table name to run the operation on",
          example = "EMPLOYEES") @PathParam("table") String table,
      @Parameter(description = "Composite primary key 1 of the record to operate on",
          example = "1") @PathParam("objectId1") String objectId1,
      @Parameter(description = "Composite primary key 2 of the record to operate on",
          example = "1") @PathParam("objectId2") String objectId2,
      @Parameter(description = "Composite primary key 3 of the record to operate on",
          example = "1") @PathParam("objectId3") String objectId3)
      throws Exception {
    delete(sc, database, table, Arrays.asList(objectId1, objectId2, objectId3));
  }

  @DELETE
  @Path("/crud/{database}/{table}/{objectId1}/{objectId2}/{objectId3}/{objectId4}")
  @Operation(summary = "deletes the object defined by the globally unique identifier")
  public void delete(@Context SecurityContext sc,
      @Parameter(description = "database name to run the operation on",
          example = "northwind") @PathParam("database") String database,
      @Parameter(description = "table name to run the operation on",
          example = "EMPLOYEES") @PathParam("table") String table,
      @Parameter(description = "Composite primary key 1 of the record to operate on",
          example = "1") @PathParam("objectId1") String objectId1,
      @Parameter(description = "Composite primary key 2 of the record to operate on",
          example = "1") @PathParam("objectId2") String objectId2,
      @Parameter(description = "Composite primary key 3 of the record to operate on",
          example = "1") @PathParam("objectId3") String objectId3,
      @Parameter(description = "Composite primary key 4 of the record to operate on",
          example = "1") @PathParam("objectId4") String objectId4)
      throws Exception {
    delete(sc, database, table, Arrays.asList(objectId1, objectId2, objectId3, objectId4));
  }

  void delete(SecurityContext sc, String database, String table, List<String> objectId)
      throws Exception {
    AbstractDatabase db = services.getConfig().getDatabase(dj(database));
    Table m = db.tables.get(table);
    ACLContainerRequestFilter.check(sc, db, m, DELETE);
    Map<String, Object> search = key(m, objectId);
    db.cast(m, search);
    if (!dbTriggers(sc, "delete", database, table, search, null, m.beforeDelete))
      return;
    if (!db.delete(m, search))
      throw new NotFoundException();
    dbTriggers(sc, "delete", database, table, search, null, m.afterDelete);
  }

  /**
   * converts the URL encoded single primary key into a map where the key is the tables's primary
   * key column name
   */
  Map<String, Object> key(Table type, List<String> id) throws Exception {
    if (type == null)
      throw new IllegalArgumentException("Unknown table");
    Map<String, Object> res = new HashMap<>();
    for (Property p : type.properties.values())
      if (p.pkpos != null) {
        if (p.pkpos >= id.size())
          throw new IllegalArgumentException("Missing composite key: " + p.name);
        String s = id.get(p.pkpos);
        res.put(p.name, s);
      }
    if (res.isEmpty())
      throw new Exception("Operation requires table with a primary key");
    return res;
  }

  /**
   * given an object and a table, returns the globally unique identifier in the form of
   * dj/database/table/ID
   */
  String key(String database, Table schema, Map<String, Object> object) {
    StringBuffer template = new StringBuffer("");
    for (int i = 0; i < schema.properties.size(); i++)
      for (Property f : schema.properties.values())
        if (f.pkpos != null)
          if (f.pkpos == i)
            template.append("/" + e(object.get(f.name)));

    if (template.length() == 0)
      return null;

    return services.getDashjoinID() + "/" + e(database) + "/" + e(schema.name) + "/"
        + template.toString().substring(1);
  }

  static String e(Object s) {
    return UrlEscapers.urlPathSegmentEscaper().escape("" + s);
  }

  String dj(String database) {
    return services.getDashjoinID() + "/" + database;
  }
}
