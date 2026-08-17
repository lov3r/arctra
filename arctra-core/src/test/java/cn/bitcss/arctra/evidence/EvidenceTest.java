package cn.bitcss.arctra.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link Evidence}.
 *
 * @author lov3r
 */
class EvidenceTest {

  @Test
  void should_create_evidence_with_valid_fields() {
    var evidence = new Evidence("queryLogs", "log content");

    assertThat(evidence.source()).isEqualTo("queryLogs");
    assertThat(evidence.content()).isEqualTo("log content");
  }

  @Test
  void should_reject_null_source() {
    assertThatThrownBy(() -> new Evidence(null, "content"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("source cannot be null or blank");
  }

  @Test
  void should_reject_blank_source() {
    assertThatThrownBy(() -> new Evidence("  ", "content"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("source cannot be null or blank");
  }

  @Test
  void should_reject_empty_source() {
    assertThatThrownBy(() -> new Evidence("", "content"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("source cannot be null or blank");
  }

  @Test
  void should_reject_null_content() {
    assertThatThrownBy(() -> new Evidence("source", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("content cannot be null");
  }

  @Test
  void should_allow_empty_content() {
    var evidence = new Evidence("source", "");

    assertThat(evidence.content()).isEmpty();
  }

  @Test
  void should_support_tool_result_as_evidence() {
    var evidence = new Evidence("queryLogs", """
        {
          "logs": [
            "16:20:15 ERROR SQLException: Unknown column 'user_status'",
            "16:20:18 ERROR SQLException: Unknown column 'user_status'"
          ]
        }
        """);

    assertThat(evidence.source()).isEqualTo("queryLogs");
    assertThat(evidence.content()).contains("SQLException");
  }
}
