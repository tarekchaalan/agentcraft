package dev.agentcraft.client.library;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.agentcraft.client.library.Markdown.Block;
import dev.agentcraft.client.library.Markdown.Span;
import dev.agentcraft.client.library.Markdown.Type;
import java.util.List;
import org.junit.jupiter.api.Test;

class MarkdownTest {
	@Test
	void nullAndEmptyInputGiveNoBlocks() {
		assertTrue(Markdown.parse(null).isEmpty());
		assertTrue(Markdown.parse("").isEmpty());
		assertTrue(Markdown.parse("\n\n  \n").isEmpty());
	}

	@Test
	void headingLevelsAreClampedToH3() {
		List<Block> b = Markdown.parse("# One\n## Two\n###### Six");
		assertEquals(Type.H1, b.get(0).type);
		assertEquals(Type.H2, b.get(1).type);
		assertEquals(Type.H3, b.get(2).type);
		assertEquals("One", b.get(0).text);
	}

	@Test
	void trailingHashesAreStrippedFromHeadings() {
		assertEquals("Title", Markdown.parse("## Title ##").get(0).text);
	}

	@Test
	void lazyLinesJoinIntoOneParagraph() {
		List<Block> b = Markdown.parse("first line\nsecond line\n\nnew para");
		assertEquals(2, b.size());
		assertEquals("first line second line", b.get(0).text);
		assertEquals(Type.PARA, b.get(1).type);
	}

	@Test
	void listsKeepTypeLevelAndCheckedState() {
		List<Block> b = Markdown.parse("- a\n  - nested\n1. one\n- [x] done\n- [ ] todo");
		assertEquals(Type.BULLET, b.get(0).type);
		assertEquals(0, b.get(0).level);
		assertEquals(1, b.get(1).level);
		assertEquals(Type.NUMBER, b.get(2).type);
		assertEquals("1.", b.get(2).marker);
		assertEquals(Type.TASK, b.get(3).type);
		assertTrue(b.get(3).checked);
		assertFalse(b.get(4).checked);
	}

	@Test
	void fencedCodeKeepsLinesAndLowercasesLang() {
		List<Block> b = Markdown.parse("```Java\nint x = 1;\n# not a heading\n```\nafter");
		assertEquals(2, b.size());
		assertEquals(Type.CODE, b.get(0).type);
		assertEquals("java", b.get(0).lang);
		assertEquals(List.of("int x = 1;", "# not a heading"), b.get(0).lines);
		assertEquals("after", b.get(1).text);
	}

	@Test
	void unterminatedFenceDoesNotThrow() {
		List<Block> b = Markdown.parse("```\nabc");
		assertEquals(1, b.size());
		assertEquals(List.of("abc"), b.get(0).lines);
	}

	@Test
	void tablesParseHeaderAndRows() {
		List<Block> b = Markdown.parse("| a | b |\n|---|---|\n| 1 | 2 |\n| 3 | 4 |");
		assertEquals(1, b.size());
		assertEquals(Type.TABLE, b.get(0).type);
		assertEquals(3, b.get(0).rows.size());
		assertEquals(List.of("a", "b"), b.get(0).rows.get(0));
		assertEquals(List.of("3", "4"), b.get(0).rows.get(2));
	}

	@Test
	void rulesAndQuotes() {
		List<Block> b = Markdown.parse("---\n> quoted\n> more");
		assertEquals(Type.RULE, b.get(0).type);
		assertEquals(Type.QUOTE, b.get(1).type);
		assertEquals("quoted more", b.get(1).text);
	}

	@Test
	void crlfLineEndingsAreNormalised() {
		assertEquals(2, Markdown.parse("# a\r\npara\r\n").size());
	}

	@Test
	void inlineStylesAreFlagged() {
		List<Span> s = Markdown.inline("a **b** `c` ~~d~~");
		assertEquals("a ", s.get(0).text());
		assertEquals(0, s.get(0).style());
		assertEquals(Markdown.BOLD, s.get(1).style());
		assertEquals("c", s.get(3).text());
		assertEquals(Markdown.CODE_SPAN, s.get(3).style());
		assertEquals(Markdown.STRIKE, s.get(5).style());
	}

	@Test
	void linkKeepsLabelOnly() {
		List<Span> s = Markdown.inline("see [docs](http://x.y) now");
		assertEquals("docs", s.get(1).text());
		assertEquals(Markdown.LINK, s.get(1).style());
	}

	@Test
	void plainStripsMarkupAndHonoursEscapes() {
		assertEquals("bold and code", Markdown.plain("**bold** and `code`"));
		assertEquals("*not italic*", Markdown.plain("\\*not italic\\*"));
	}

	@Test
	void unclosedMarkersDegradeToText() {
		assertEquals("a `oops", Markdown.plain("a `oops"));
		assertEquals("a * b", Markdown.plain("a * b"));
	}
}
