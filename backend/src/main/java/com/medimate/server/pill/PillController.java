package com.medimate.server.pill;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;
import com.medimate.server.common.Api;
import com.medimate.server.common.FirebaseAuthFilter;
import com.medimate.server.common.Enums.ColorClass;
import com.medimate.server.common.Enums.DrugShape;
import com.medimate.server.common.Enums.LineType;
import com.medimate.server.pill.PillDtos.IdentificationRequest;
import com.medimate.server.pill.PillDtos.JobAccepted;
import com.medimate.server.pill.PillDtos.JobView;
import com.medimate.server.pill.PillDtos.SelectRequest;
import com.medimate.server.pill.PillDtos.SelectResult;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/pill-identification")
public class PillController {

    private final PillService pills;

    public PillController(PillService pills) {
        this.pills = pills;
    }

    @PostMapping
    ResponseEntity<Api.Response<JobAccepted>> request(@Valid @RequestBody IdentificationRequest body,
                                                      HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Api.ok(pills.submit(body, FirebaseAuthFilter.uid(request),
                FirebaseAuthFilter.clientKey(request))));
    }

    @GetMapping("/{jobId}")
    Api.Response<JobView> get(@PathVariable String jobId, HttpServletRequest request) {
        return Api.ok(pills.get(jobId, FirebaseAuthFilter.uid(request)));
    }

    @PostMapping("/{jobId}/select")
    Api.Response<SelectResult> select(@PathVariable String jobId, @Valid @RequestBody SelectRequest body,
                                      HttpServletRequest request) {
        return Api.ok(pills.select(jobId, body, FirebaseAuthFilter.uid(request)));
    }

    @GetMapping("/search")
    Api.Response<Api.Page<JsonNode>> search(@RequestParam(required = false) DrugShape drugShape,
                                            @RequestParam(required = false) List<ColorClass> colorClass,
                                            @RequestParam(required = false) String printText,
                                            @RequestParam(required = false) LineType lineFront,
                                            @RequestParam(required = false) String itemName,
                                            @RequestParam(required = false) String cursor,
                                            @RequestParam(required = false) Integer size) {
        return Api.ok(pills.search(drugShape, colorClass, printText, lineFront, itemName, cursor, size));
    }
}
