package org.dashjoin.service;

import static org.dashjoin.util.MapUtil.of;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import javax.inject.Inject;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.PATCH;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Request;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;
import org.apache.commons.collections4.IteratorUtils;
import org.dashjoin.function.FunctionService;
import org.dashjoin.util.OpenAPI;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * generic REST interceptor that proxies between an OpenAPI spec and the respective DJ functions
 */
@Path(Services.REST_PREFIX + "app")
@Produces({MediaType.APPLICATION_JSON})
public class AppAPI {

  @Inject
  Services services;

  @Inject
  Data data;

  @Inject
  FunctionService functionService;

  /**
   * generic request without body
   */
  @GET
  @POST
  @PUT
  @PATCH
  @DELETE
  @Path("/{s:.*}")
  public Object get(@Context SecurityContext sc, @Context Request request, @Context UriInfo info,
      @Context HttpHeaders headers, @PathParam("s") String s) throws Exception {
    return handle(request.getMethod().toLowerCase(), sc, info, headers, s, null);
  }

  /**
   * generic request with body
   */
  @POST
  @PUT
  @PATCH
  @DELETE
  @Path("/{s:.*}")
  @Consumes({MediaType.APPLICATION_JSON})
  public Object post(@Context SecurityContext sc, @Context Request request, @Context UriInfo info,
      @Context HttpHeaders headers, @PathParam("s") String s, Object body) throws Exception {
    return handle(request.getMethod().toLowerCase(), sc, info, headers, s, body);
  }

  Object handle(String method, SecurityContext sc, UriInfo info, HttpHeaders headers, String s,
      Object body) throws Exception {
    JsonNode spec = OpenAPI.open(services);
    if (spec == null)
      throw new Exception("No API defined");
    JsonNode paths = spec.get("paths");
    if (paths == null)
      throw new Exception("No API defined");

    for (Entry<String, JsonNode> path : IteratorUtils.toList(paths.fields())) {
      Map<String, Object> pathpar = OpenAPI.matchPath(path.getKey(), "/" + s);
      if (pathpar != null) {
        JsonNode m = path.getValue().get(method);
        if (m == null)
          throw new Exception("Method " + method + " not supported for path " + path.getKey());

        JsonNode operationId = m.get("operationId");
        if (operationId == null)
          throw new Exception("No operationId set for path " + path.getKey());

        Map<String, Object> parameters = null;
        if (m.get("parameters") != null) {
          parameters = of();
          for (JsonNode p : m.get("parameters")) {
            String name = p.get("name").asText();
            switch (p.get("in").asText()) {
              case "query":
                List<String> x = info.getQueryParameters().get(name);
                if (x != null)
                  parameters.put(name, x);
                continue;
              case "path":
                if (pathpar.get(name) != null)
                  parameters.put(name, pathpar.get(name));
                continue;
              case "header":
                if (headers.getRequestHeader(name) != null)
                  parameters.put(name, headers.getRequestHeader(name));
                continue;
              case "cookie":
                if (headers.getCookies().get(name) != null)
                  if (headers.getCookies().get(name).getValue() != null)
                    parameters.put(name, headers.getCookies().get(name).getValue());
                continue;
            }
          }
        }

        return functionService.call(sc, operationId.asText(),
            of("parameters", parameters, "body", body));
      }
    }
    throw new Exception("No implementation found for path " + s);
  }

  /**
   * proxy data.query
   */
  @POST
  @Path("/rest/database/query/{database}/{queryId}")
  @Consumes({MediaType.APPLICATION_JSON})
  public List<Map<String, Object>> query(@Context SecurityContext sc,
      @PathParam("database") String database, @PathParam("queryId") String queryId,
      Map<String, Object> arguments) throws Exception {
    return data.query(sc, database, queryId, arguments);
  }

  /**
   * proxy function.call
   */
  @POST
  @Path("/rest/function/{function}")
  @Consumes({MediaType.APPLICATION_JSON})
  public Object call(@Context SecurityContext sc, @PathParam("function") String function,
      Object argument) throws Exception {
    return functionService.call(sc, function, argument);
  }
}
