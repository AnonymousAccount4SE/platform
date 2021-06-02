package org.dashjoin.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.SecurityContext;
import org.dashjoin.service.Manage.DetectResult;
import org.jboss.resteasy.plugins.providers.multipart.InputPart;
import org.jboss.resteasy.plugins.providers.multipart.MultipartFormDataInput;
import org.junit.Assert;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Matchers;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
public class ManageTest {

  @Inject
  Services services;

  @Test
  public void testGetFileName() {
    MultivaluedMap<String, String> headers = new MultivaluedHashMap<>();
    headers.put("Content-Disposition", Arrays.asList("filename=\"filename.jpg\""));
    String name = new Manage().getFileName(headers);
    Assert.assertEquals("filename", name);
  }

  @Test
  public void testGetFileNameEx() {
    Assertions.assertThrows(RuntimeException.class, () -> {
      MultivaluedMap<String, String> headers = new MultivaluedHashMap<>();
      headers.put("Content-Disposition", Arrays.asList("bla"));
      new Manage().getFileName(headers);
    });
  }

  @Test
  public void detectExisting() throws Exception {
    DetectResult dr = detect("PRJ.csv", "ID,NAME\n17,import");
    Assert.assertEquals(false, dr.createMode);
    Assert.assertEquals(true, dr.schema.get("PRJ").get(0).pk);
    Assert.assertEquals("ID", dr.schema.get("PRJ").get(0).name);
    Assert.assertEquals("17", dr.schema.get("PRJ").get(0).sample.get(0));
    Assert.assertEquals("number", dr.schema.get("PRJ").get(0).type);
    Assert.assertEquals(false, dr.schema.get("PRJ").get(1).pk);
    Assert.assertEquals("NAME", dr.schema.get("PRJ").get(1).name);
    Assert.assertEquals("import", dr.schema.get("PRJ").get(1).sample.get(0));
    Assert.assertEquals("string", dr.schema.get("PRJ").get(1).type);
  }

  @Test
  public void detectNew() throws Exception {
    DetectResult dr = detect("NEW.csv", "B,I,D,S\ntrue,42,3.14,hello\ntrue,42,3.14,pk");
    Assert.assertEquals(true, dr.createMode);
    Assert.assertEquals(false, dr.schema.get("NEW").get(0).pk);
    Assert.assertEquals("B", dr.schema.get("NEW").get(0).name);
    Assert.assertEquals(true, dr.schema.get("NEW").get(0).sample.get(0));
    Assert.assertEquals("boolean", dr.schema.get("NEW").get(0).type);
    Assert.assertEquals(false, dr.schema.get("NEW").get(1).pk);
    Assert.assertEquals("I", dr.schema.get("NEW").get(1).name);
    Assert.assertEquals(42, dr.schema.get("NEW").get(1).sample.get(0));
    Assert.assertEquals("integer", dr.schema.get("NEW").get(1).type);
    Assert.assertEquals(false, dr.schema.get("NEW").get(2).pk);
    Assert.assertEquals("D", dr.schema.get("NEW").get(2).name);
    Assert.assertEquals(3.14, dr.schema.get("NEW").get(2).sample.get(0));
    Assert.assertEquals("number", dr.schema.get("NEW").get(2).type);
    Assert.assertEquals(true, dr.schema.get("NEW").get(3).pk);
    Assert.assertEquals("S", dr.schema.get("NEW").get(3).name);
    Assert.assertEquals("hello", dr.schema.get("NEW").get(3).sample.get(0));
    Assert.assertEquals("string", dr.schema.get("NEW").get(3).type);
  }

  @Test
  public void detectMissingValues() throws Exception {
    detect("NEW.csv", "ID,NAME,AGE\n17.3,import,2\n,test");
  }

  @Test
  public void detectColMismatch() throws Exception {
    Assertions.assertThrows(Exception.class, () -> {
      detect("PRJ.csv", "NAME\nimport");
    });
  }

  @Test
  public void detectMixedTables() throws Exception {
    Assertions.assertThrows(Exception.class, () -> {
      detect("PRJ.csv", "ID,NAME\n17,import", "UNKNOWN.csv", "COL");
    });
  }

  @Test
  public void detectMixedTables2() throws Exception {
    Assertions.assertThrows(Exception.class, () -> {
      detect("UNKNOWN.csv", "COL", "PRJ.csv", "ID,NAME\n17,import");
    });
  }

  DetectResult detect(String filename, String csv) throws Exception {
    return detect(filename, csv, null, null);
  }

  DetectResult detect(String filename, String csv, String filename2, String csv2) throws Exception {
    SecurityContext sc = mock(SecurityContext.class);
    when(sc.isUserInRole(Matchers.anyString())).thenReturn(true);

    MultivaluedMap<String, String> headers = new MultivaluedHashMap<>();
    headers.put("Content-Disposition", Arrays.asList("filename=\"" + filename + "\""));
    InputPart token = mock(InputPart.class);
    when(token.getHeaders()).thenReturn(headers);
    when(token.getBody(InputStream.class, null))
        .thenReturn(new ByteArrayInputStream(csv.getBytes()));

    MultivaluedMap<String, String> headers2 = new MultivaluedHashMap<>();
    headers2.put("Content-Disposition", Arrays.asList("filename=\"" + filename2 + "\""));
    InputPart token2 = mock(InputPart.class);
    when(token2.getHeaders()).thenReturn(headers2);
    if (csv2 != null)
      when(token2.getBody(InputStream.class, null))
          .thenReturn(new ByteArrayInputStream(csv2.getBytes()));

    Map<String, List<InputPart>> paramsMap = new HashMap<>();
    if (csv2 == null)
      paramsMap.put("file", Arrays.asList(token));
    else
      paramsMap.put("file", Arrays.asList(token, token2));

    MultipartFormDataInput input = mock(MultipartFormDataInput.class);
    when(input.getFormDataMap()).thenReturn(paramsMap);

    Manage m = new Manage();
    m.services = services;
    return m.detect(sc, "junit", input);
  }
}