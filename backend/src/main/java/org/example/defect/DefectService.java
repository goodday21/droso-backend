package org.example.defect;

import org.example.defect.model.CreateDefectRequest;
import org.example.defect.model.DefectResponse;
import org.example.web.error.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Use cases of the defects. The entity never leaves this class: the web layer gets a
 * {@link DefectResponse}.
 */
@Service
public class DefectService {

    private final DefectRepository repository;

    public DefectService(DefectRepository repository) {
        this.repository = repository;
    }

    /**
     * Creates a defect with the status {@link DefectStatus#NEW}. The request is expected to be
     * validated already; {@link Defect} still enforces the title rule on its own.
     *
     * @param authorId the id of the user who reports it, from the access token (never from the body)
     */
    @Transactional
    public DefectResponse create(CreateDefectRequest request, long authorId) {
        Defect defect = new Defect(request.title(), request.description(), request.stepsToReproduce(),
                request.expectedResult(), request.actualResult(), request.priority(), authorId);
        // saveAndFlush: the INSERT runs here, so the id and the timestamps of the response are the
        // ones of the stored row, and a failure of the database surfaces inside this method.
        return DefectResponse.of(repository.saveAndFlush(defect));
    }

    /**
     * Returns the stored defect with the given id.
     *
     * <p>Read-only: Hibernate does not flush the session, and the connection is put into read-only
     * mode, so PostgreSQL itself refuses a write by mistake.
     *
     * @throws ResourceNotFoundException if there is no defect with this id
     */
    @Transactional(readOnly = true)
    public DefectResponse get(long id) {
        return repository.findById(id)
                .map(DefectResponse::of)
                .orElseThrow(() -> new ResourceNotFoundException("Defect", id));
    }
}
