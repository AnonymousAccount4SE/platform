package org.dashjoin.service;

import java.util.Arrays;
import java.util.Map;
import org.dashjoin.model.Table;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryException;
import org.junit.Assert;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

public class MetadataTest {

  private static final ObjectMapper om = new ObjectMapper();

  @Test
  public void subclass() throws Exception {
    RDF4J db = new RDF4J() {
      @Override
      RepositoryConnection getConnection() throws RepositoryException {
        return _cp.getConnection();
      }
    };
    db.ID = "dj/meta";
    db.datasets = Arrays.asList("/data/meta.n3");
    Map<String, Table> meta =
        om.convertValue(db.connectAndCollectMetadata(), new TypeReference<Map<String, Table>>() {});
    // Map<String, Object> meta = db.connectAndCollectMetadata();

    // infer a subclass to be a type also
    Assert.assertTrue(meta.containsKey("http://ex.org/EMP"));

    // infer a subclass to also inherit properties
    Assert
        .assertTrue(meta.get("http://ex.org/INTERN").properties.containsKey("http://ex.org/NAME"));
    Assert.assertTrue(meta.get("http://ex.org/EMP").properties.containsKey("http://ex.org/NAME"));
    name(meta);
  }

  void name(Map<String, Table> meta) {
    Assert.assertEquals("http://ex.org/NAME",
        meta.get("http://ex.org/EMP").properties.get("http://ex.org/NAME").name);
    Assert.assertEquals("dj/meta/http:%2F%2Fex.org%2FEMP/http:%2F%2Fex.org%2FNAME",
        meta.get("http://ex.org/EMP").properties.get("http://ex.org/NAME").ID);
    Assert.assertEquals("dj/meta/http:%2F%2Fex.org%2FEMP",
        meta.get("http://ex.org/EMP").properties.get("http://ex.org/NAME").parent);
    Assert.assertEquals("string",
        meta.get("http://ex.org/EMP").properties.get("http://ex.org/NAME").type);
    Assert.assertEquals("NAME",
        meta.get("http://ex.org/EMP").properties.get("http://ex.org/NAME").title);

    Assert.assertEquals("dj/meta/http:%2F%2Fex.org%2FPRJ/ID",
        meta.get("http://ex.org/EMP").properties.get("http://ex.org/WORKSON").ref);

    Assert.assertEquals("array",
        meta.get("http://ex.org/EMP").properties.get("http://ex.org/EMAIL").type);
    Assert.assertEquals("string",
        meta.get("http://ex.org/EMP").properties.get("http://ex.org/EMAIL").items.type);
  }

  @Test
  public void detectProps() throws Exception {
    RDF4J db = new RDF4J() {
      @Override
      RepositoryConnection getConnection() throws RepositoryException {
        return _cp.getConnection();
      }
    };
    db.ID = "dj/meta";
    db.datasets = Arrays.asList("/data/props.n3");
    Map<String, Table> meta =
        om.convertValue(db.connectAndCollectMetadata(), new TypeReference<Map<String, Table>>() {});

    name(meta);
  }
}
