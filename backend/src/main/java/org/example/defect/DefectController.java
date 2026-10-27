package org.example.defect;

import java.net.URI;

import jakarta.validation.Valid;
import org.example.defect.model.CreateDefectRequest;
import org.example.defect.model.DefectResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API of the defects.
 *
 * <p>Invalid requests are answered by {@code ApiExceptionHandler} with {@code 400 VALIDATION_ERROR}
 * or {@code 400 MALFORMED_REQUEST}.
 */
@RestController
@RequestMapping(DefectController.PATH)
public class DefectController {

    static final String PATH = "/api/v1/defects";

    private final DefectService defectService;

    public DefectController(DefectService defectService) {
        this.defectService = defectService;
    }

    /**
     * Creates a defect and answers {@code 201 Created} with the stored defect.
     *
     * <p>The {@code Location} is relative (RFC 9110 allows it): it stays correct behind a reverse
     * proxy, which an absolute URL built from the request would not without the forwarded headers.
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<DefectResponse> create(@Valid @RequestBody CreateDefectRequest request) {
        DefectResponse created = defectService.create(request);
        return ResponseEntity.created(URI.create(PATH + "/" + created.id())).body(created);
    }
}
