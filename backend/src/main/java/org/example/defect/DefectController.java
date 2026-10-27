package org.example.defect;

import java.net.URI;

import jakarta.validation.Valid;
import org.example.defect.model.CreateDefectRequest;
import org.example.defect.model.DefectResponse;
import org.example.web.CanonicalIdEditor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API of the defects.
 *
 * <p>Invalid requests are answered by {@code ApiExceptionHandler} with {@code 400 VALIDATION_ERROR}
 * or {@code 400 MALFORMED_REQUEST}, a missing defect with {@code 404 RESOURCE_NOT_FOUND}.
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
     * Accepts the ids of the URLs in their canonical form only ({@code 26}, not {@code 0x1A} or
     * {@code 026}); anything else answers {@code 400 VALIDATION_ERROR}. See {@link CanonicalIdEditor}.
     */
    @InitBinder
    void initBinder(WebDataBinder binder) {
        binder.registerCustomEditor(Long.class, new CanonicalIdEditor());
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

    /**
     * Answers {@code 200 OK} with the stored defect, {@code 404 RESOURCE_NOT_FOUND} if there is none.
     */
    @GetMapping("/{id}")
    public DefectResponse get(@PathVariable Long id) {
        return defectService.get(id);
    }
}
