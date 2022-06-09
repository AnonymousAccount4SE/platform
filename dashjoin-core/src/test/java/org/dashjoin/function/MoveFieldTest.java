package org.dashjoin.function;

import static java.util.Arrays.asList;
import static org.dashjoin.util.MapUtil.of;
import java.util.Arrays;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class MoveFieldTest {

  @Test
  public void testIgoreIfToIsNoObject() throws Exception {
    MoveField m = new MoveField();
    Object res = m.run(Arrays.asList(of("x", 1, "y", asList(1)), "x", "y", null));
    Assertions.assertEquals("{y=[1]}", "" + res);
  }

  @Test
  public void testMoveToArray() throws Exception {
    MoveField m = new MoveField();
    Object res = m.run(Arrays.asList(of("x", 1, "y", asList(of(), of())), "x", "y", null));
    Assertions.assertEquals("{y=[{x=1}, {x=1}]}", "" + res);
  }

  @Test
  public void testMoveToMap() throws Exception {
    MoveField m = new MoveField();
    Object res = m.run(Arrays.asList(of("x", 1, "y", of()), "x", "y", null));
    Assertions.assertEquals("{y={x=1}}", "" + res);
  }

  @Test
  public void testCollision() throws Exception {
    MoveField m = new MoveField();
    Object res = m.run(Arrays.asList(of("x", 1, "y", of("x", 2)), "x", "y", "parent"));
    Assertions.assertEquals("{y={x=2, parent=1}}", "" + res);
  }
}
