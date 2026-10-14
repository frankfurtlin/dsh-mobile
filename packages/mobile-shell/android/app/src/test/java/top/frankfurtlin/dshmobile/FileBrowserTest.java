package top.frankfurtlin.dshmobile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * Path addressing is what the agent and the phone must agree on, so it is pinned
 * here: one string per location, two roots, and no way out of either root.
 */
public class FileBrowserTest {

  @Test
  public void parsesTreeRootInEverySpelling() {
    for (String input : new String[]{"tree:", "tree:/", "tree://", "tree:///"}) {
      FileBrowser.Target target = FileBrowser.parsePath(input);
      assertNotNull(input, target);
      assertEquals("tree", target.kind);
      assertEquals("", target.rel);
      assertEquals("tree:/", target.path());
    }
  }

  @Test
  public void parsesDownloadsPaths() {
    FileBrowser.Target target = FileBrowser.parsePath("downloads:/notes/todo.md");
    assertNotNull(target);
    assertEquals("downloads", target.kind);
    assertEquals("notes/todo.md", target.rel);
    assertEquals("todo.md", target.name());
    assertEquals("notes", target.parentRel());
    assertEquals("tree", FileBrowser.parsePath("tree:/a").kind);
  }

  @Test
  public void normalisesRedundantSeparatorsAndDots() {
    FileBrowser.Target target = FileBrowser.parsePath("  tree:///Documents//sub/./report.pdf/  ");
    assertNotNull(target);
    assertEquals("Documents/sub/report.pdf", target.rel);
    assertEquals("tree", target.kind);
  }

  @Test
  public void rejectsEscapingTheRoot() {
    assertNull(FileBrowser.parsePath("tree:/../etc/passwd"));
    assertNull(FileBrowser.parsePath("tree:/Documents/../.."));
    assertNull(FileBrowser.parsePath("downloads:/a/../../b"));
  }

  @Test
  public void rejectsUnknownOrMissingPrefix() {
    assertNull(FileBrowser.parsePath(null));
    assertNull(FileBrowser.parsePath(""));
    assertNull(FileBrowser.parsePath("/Documents/report.pdf"));
    assertNull(FileBrowser.parsePath("content://com.android.providers/document/1"));
    assertNull(FileBrowser.parsePath("file:/sdcard/x.txt"));
  }

  @Test
  public void parentOfRootStaysAtRoot() {
    FileBrowser.Target root = FileBrowser.parsePath("tree:/");
    assertNotNull(root);
    assertEquals("", root.parent().rel);
    assertEquals("tree", root.parent().kind);
  }
}
