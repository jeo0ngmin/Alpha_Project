package com.medimate.server.recognition;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.medimate.server.common.Api;
import com.medimate.server.common.FirebaseAuthFilter;
import com.medimate.server.pill.PillDtos.JobAccepted;
import com.medimate.server.recognition.RecognitionDtos.BagItem;
import com.medimate.server.recognition.RecognitionDtos.ConfirmRequest;
import com.medimate.server.recognition.RecognitionDtos.ConfirmResult;
import com.medimate.server.recognition.RecognitionDtos.ItemPatch;
import com.medimate.server.recognition.RecognitionDtos.RecognitionJob;
import com.medimate.server.recognition.RecognitionDtos.RecognitionRequest;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/recognition")
public class RecognitionController {

    private final RecognitionService recognitions;

    public RecognitionController(RecognitionService recognitions) {
        this.recognitions = recognitions;
    }

    @PostMapping("/medication-bags")
    ResponseEntity<Api.Response<JobAccepted>> request(@Valid @RequestBody RecognitionRequest body, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Api.ok(recognitions.submit(body,
                FirebaseAuthFilter.uid(request), FirebaseAuthFilter.clientKey(request))));
    }

    @GetMapping("/jobs/{jobId}")
    Api.Response<RecognitionJob> get(@PathVariable String jobId, HttpServletRequest request) {
        return Api.ok(recognitions.get(jobId, FirebaseAuthFilter.uid(request)));
    }

    @PatchMapping("/jobs/{jobId}/items/{itemId}")
    Api.Response<BagItem> patch(@PathVariable String jobId, @PathVariable String itemId,
                                @Valid @RequestBody ItemPatch body, HttpServletRequest request) {
        return Api.ok(recognitions.patch(jobId, itemId, body, FirebaseAuthFilter.uid(request)));
    }

    @PostMapping("/jobs/{jobId}/confirm")
    ResponseEntity<Api.Response<ConfirmResult>> confirm(@PathVariable String jobId,
                                                        @RequestBody(required = false) ConfirmRequest body,
                                                        HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Api.ok(recognitions.confirm(jobId, body, FirebaseAuthFilter.uid(request))));
    }
}
