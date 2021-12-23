package org.dashjoin.util;

import java.io.File;
import java.net.URL;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
public class FileSystemTest {

  @Test
  public void testCheckFileAccess() throws Exception {
    // only allow access to the upload folder
    Assertions.assertThrows(RuntimeException.class, () -> {
      FileSystem.checkFileAccess(new URL("file:."));
    });

    FileSystem.checkFileAccess(new File("upload"));
    FileSystem.checkFileAccess(new URL("file:upload"));
  }

  @Test
  public void testUploadUrl() throws Exception {
    System.err.println(FileSystem.getUploadURL("file:upload/test.txt"));

    // Other proto than file is OK
    System.err.println(FileSystem.getUploadURL("http://example.org/download/test.txt"));

    Assertions.assertThrows(RuntimeException.class, () -> {
      System.err.println(FileSystem.getUploadURL("file:test.txt"));
    });

    Assertions.assertThrows(RuntimeException.class, () -> {
      System.err.println(FileSystem.getUploadURL("file:../upload/test.txt"));
    });

    Assertions.assertThrows(RuntimeException.class, () -> {
      System.err.println(FileSystem.getUploadURL("file:/../upload/test.txt"));
    });
  }

  @Test
  public void testJdbcUrl() throws Exception {

    System.out.println(FileSystem.getJdbcUrl("jdbc:h2:mem:test"));

    System.out.println(FileSystem.getJdbcUrl("jdbc:sqlite:dashjoin-demo.db"));

    System.out.println(FileSystem.getJdbcUrl("jdbc:SQLite:dashjoin-demo.db"));

    Assertions.assertThrows(RuntimeException.class, () -> {
      System.out.println(FileSystem.getJdbcUrl("jdbc:h2:../TestDataBase"));
    });

    // tmp folder is ok (for appengine)
    System.out.println(FileSystem.getJdbcUrl("jdbc:h2:/tmp/TestDataBase"));
    System.out.println(FileSystem.getJdbcUrl("jdbc:sqlite:/tmp/sub/dashjoin-demo.db"));

    System.out.println(FileSystem.getJdbcUrl("jdbc:postgresql://your_host:5432/your_database"));
  }

  @Test
  public void testUploadUrlRanges() throws Exception {
    // Check file range handling
    URL url = FileSystem.getUploadURL("file:upload/test.txt?start=99&size=456");
    System.err.println(url);
    FileResource fr = FileResource.of(url.toString());
    assert (fr.size == 456 && fr.start == 99);
  }
}
