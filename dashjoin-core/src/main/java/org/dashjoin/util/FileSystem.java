package org.dashjoin.util;

import java.io.File;
import java.io.IOException;
import java.net.URL;

/**
 * makes sure no file access is allowed except for the upload folder
 */
public class FileSystem {

  /**
   * checks file URLs, ignores the other protocols
   */
  public static void checkFileAccess(URL url) throws IOException {
    if (url.getProtocol().equals("file")) {
      checkFileAccess(new File(url.getPath()));
    }
  }

  /**
   * checks file objects, . and .. are normalized
   */
  public static void checkFileAccess(File file) throws IOException {

    String upload = Home.get().getFile("upload").getCanonicalPath();
    // File("").getCanonicalPath() + File.separator + "upload";
    String test = file.getCanonicalPath();

    if (!test.startsWith(upload))
      throw new RuntimeException("You do not have access to the folder '" + file
          + "'. Choose a file in the upload folder.");
  }

  public static File getUploadFile(String name) throws IOException {
    return Home.get().getFile(name);
  }

  public static URL getUploadURL(String name) throws IOException {
    URL url = new URL(name);
    if (url.getProtocol().equals("file")) {
      url = new URL("file:" + Home.get().getFile(url.getPath()).getCanonicalPath()
          + (url.getQuery() != null ? "?" + url.getQuery() : ""));
      checkFileAccess(new File(url.getPath()));
    }
    return url;
  }

  public static String getJdbcUrl(String url) throws IOException {
    if (url.toLowerCase().startsWith("jdbc:sqlite:")) {
      String file = url.substring("jdbc:sqlite:".length());
      checkSQLiteAccess(new File(file));
      return "jdbc:sqlite:" + Home.get().getFile(file);
    }
    if (url.toLowerCase().startsWith("jdbc:h2:") && !url.toLowerCase().startsWith("jdbc:h2:mem:")) {
      String file = url.substring("jdbc:h2:".length());
      checkSQLiteAccess(new File(file));
      return "jdbc:h2:" + Home.get().getFile(file);
    }
    return url;
  }

  /**
   * checks file objects, . and .. are normalized
   */
  public static void checkSQLiteAccess(File file) throws IOException {

    String root = Home.get().getFile(".").getCanonicalPath();
    String test = file.getCanonicalPath();
    if (!test.startsWith(root))
      throw new RuntimeException("You do not have access to the folder '" + file
          + "'. Choose a file in the application folder.");
  }

}
