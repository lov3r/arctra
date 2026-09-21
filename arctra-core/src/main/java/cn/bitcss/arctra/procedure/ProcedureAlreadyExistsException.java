package cn.bitcss.arctra.procedure;

/**
 * Exception thrown when attempting to create a procedure revision that already exists.
 *
 * @author lov3r
 * @since M8-A
 */
public class ProcedureAlreadyExistsException extends RuntimeException {

  private final String procedureId;
  private final int revision;

  public ProcedureAlreadyExistsException(String procedureId, int revision) {
    super("Procedure revision already exists: " + procedureId + " revision " + revision);
    this.procedureId = procedureId;
    this.revision = revision;
  }

  public String procedureId() {
    return procedureId;
  }

  public int revision() {
    return revision;
  }
}
