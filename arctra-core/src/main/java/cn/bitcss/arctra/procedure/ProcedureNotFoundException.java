package cn.bitcss.arctra.procedure;

/**
 * Exception thrown when a procedure revision cannot be found.
 *
 * @author lov3r
 * @since M8-A
 */
public class ProcedureNotFoundException extends RuntimeException {

  private final String procedureId;
  private final Integer revision;

  public ProcedureNotFoundException(String procedureId) {
    super("Procedure not found: " + procedureId);
    this.procedureId = procedureId;
    this.revision = null;
  }

  public ProcedureNotFoundException(String procedureId, int revision) {
    super("Procedure revision not found: " + procedureId + " revision " + revision);
    this.procedureId = procedureId;
    this.revision = revision;
  }

  public String procedureId() {
    return procedureId;
  }

  public Integer revision() {
    return revision;
  }
}
