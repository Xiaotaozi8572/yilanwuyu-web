package com.yilan.memory.adapter.rest;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Serves the local control-center entry page without relying on welcome-page lookup. */
@RestController
public final class ControlCenterPageController {

    @GetMapping(path = {"/memory-control", "/memory-control/"})
    public ResponseEntity<Resource> page() {
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(new ClassPathResource("static/memory-control/index.html"));
    }
}
