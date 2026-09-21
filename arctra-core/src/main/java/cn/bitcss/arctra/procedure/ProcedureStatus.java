package cn.bitcss.arctra.procedure;

/**
 * Lifecycle status of a reusable procedure revision.
 *
 * <p><strong>M8-A V1:</strong> Minimal lifecycle model. Status changes do NOT mutate immutable
 * executable definition.
 *
 * <ul>
 *   <li><strong>VALID</strong> - revision is active and executable
 *   <li><strong>INVALID</strong> - revision should not be used (e.g., repeated failures)
 *   <li><strong>SUPERSEDED</strong> - newer revision exists, but old revision remains for suspended
 *       executions
 * </ul>
 *
 * <p>Physical deletion is avoided because suspended executions may reference old revisions.
 *
 * @author lov3r
 * @since M8-A
 */
public enum ProcedureStatus {

  /** Revision is valid and may be executed. */
  VALID,

  /** Revision should not be executed (e.g., tool incompatibility, repeated failures). */
  INVALID,

  /** Revision has been superseded by newer revision but remains for historical reference. */
  SUPERSEDED
}
