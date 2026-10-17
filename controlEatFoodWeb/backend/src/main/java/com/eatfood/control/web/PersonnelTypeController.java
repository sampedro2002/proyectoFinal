package com.eatfood.control.web;
import com.eatfood.control.dto.PersonnelTypeDtos.*;
import com.eatfood.control.service.PersonnelTypeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController @RequestMapping("/api/personnel-types") @RequiredArgsConstructor
public class PersonnelTypeController {
    private final PersonnelTypeService service;
    @GetMapping public List<Response> list(@RequestParam(defaultValue="false") boolean includeInactive){ return service.list(includeInactive); }
    @PostMapping @PreAuthorize("hasRole('ADMIN')") public Response create(@Valid @RequestBody Request req){ return service.create(req); }
    @PutMapping("/{id}") @PreAuthorize("hasRole('ADMIN')") public Response update(@PathVariable Long id,@Valid @RequestBody UpdateRequest req){ return service.update(id,req); }
}
