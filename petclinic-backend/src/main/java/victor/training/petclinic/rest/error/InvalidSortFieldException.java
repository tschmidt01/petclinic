package victor.training.petclinic.rest.error;

/**
 * Thrown when the caller requests sorting by a field that is not in the allowed sort-field whitelist.
 */
public class InvalidSortFieldException extends RuntimeException {

    public InvalidSortFieldException(String message) {
        super(message);
    }
}
