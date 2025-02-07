package org.dashjoin.function;

import java.net.URL;
import java.util.Arrays;
import java.util.List;
import org.dashjoin.util.Home;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.internal.storage.file.FileRepository;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.microprofile.config.ConfigProvider;

public class GitCommit extends AbstractVarArgFunction<String> {

  @SuppressWarnings("rawtypes")
  @Override
  public String run(List args) throws Exception {
    URL url = new URL(ConfigProvider.getConfig().getConfigValue("dashjoin.appurl").getValue());
    String user = url.getUserInfo();
    if (user == null)
      throw new IllegalArgumentException("No Git credentials configured in the App URL");
    String message = (String) args.get(0);
    List paths = (List) args.get(1);
    if (message == null || paths == null || paths.isEmpty())
      throw new IllegalArgumentException("Arguments required: $gitCommit(message, [paths])");
    try (Git git = new Git(new FileRepository(Home.get().getHome() + "/.git"))) {
      for (Object s : paths)
        git.add().addFilepattern("" + s).call();
      git.commit().setMessage(message).call();
      git.push()
          .setCredentialsProvider(
              new UsernamePasswordCredentialsProvider(user.split(":")[0], user.split(":")[1]))
          .call();
      return "Ok";
    }
  }

  @Override
  public String getID() {
    return "gitCommit";
  }

  @Override
  public String getType() {
    return "write";
  }

  @SuppressWarnings("rawtypes")
  @Override
  public List<Class> getArgumentClassList() {
    return Arrays.asList(String.class, List.class);
  }
}
