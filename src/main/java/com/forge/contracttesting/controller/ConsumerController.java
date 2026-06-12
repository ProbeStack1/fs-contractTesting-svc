package com.forge.contracttesting.controller;

import com.forge.contracttesting.dto.ApiResponse;
import com.forge.contracttesting.dto.ConsumerDto;
import com.forge.contracttesting.model.Consumer;
import com.forge.contracttesting.model.Contract;
import com.forge.contracttesting.service.ConsumerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/v1/consumers")
@RequiredArgsConstructor
@CrossOrigin(origins = "*", methods = {
        RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT,
        RequestMethod.PATCH, RequestMethod.DELETE, RequestMethod.OPTIONS
})
public class ConsumerController {

    private final ConsumerService consumerService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<Consumer>>> list() {
        return ResponseEntity.ok(ApiResponse.success(consumerService.listAll()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<Consumer>> get(@PathVariable String id) {
        return ResponseEntity.ok(ApiResponse.success(consumerService.getById(id)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Consumer>> create(
            @Valid @RequestBody ConsumerDto dto) {
        log.info("Create consumer: {}", dto.getConsumerName());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Consumer created successfully", consumerService.create(dto)));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<ApiResponse<Consumer>> update(
            @PathVariable String id,
            @RequestBody ConsumerDto dto) {
        return ResponseEntity.ok(ApiResponse.success("Consumer updated successfully",
                consumerService.update(id, dto)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable String id) {
        consumerService.delete(id);
        return ResponseEntity.ok(ApiResponse.success("Consumer deleted successfully", null));
    }

    /** Returns all contracts where consumerName matches this consumer. */
    @GetMapping("/{id}/contracts")
    public ResponseEntity<ApiResponse<List<Contract>>> getContracts(@PathVariable String id) {
        return ResponseEntity.ok(ApiResponse.success(
                consumerService.getContractsByConsumer(id)));
    }
}
