package org.dashjoin.service;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
public class ArangoDBTest extends DBTest {

  @Override
  protected Object toID(Object o) {
    if (o.equals(1))
      return "EMP/" + o;
    if (o.equals("1"))
      return "EMP/" + o;
    if (o.equals("2"))
      return "EMP/" + o;
    if (o.equals("1000"))
      return "PRJ/" + o;
    if (o instanceof Integer)
      return "PRJ/" + o;
    return o;
  }

  @Override
  protected Object thousand() {
    return 1000l;
  }

  @Override
  protected String idRead() {
    return "_id";
  }

  @Override
  protected String idQuery() {
    return "_id";
  }
}
