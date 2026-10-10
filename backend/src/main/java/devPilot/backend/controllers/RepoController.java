package devPilot.backend.controllers;

import devPilot.backend.DTO.IndexStatusResponse;
import devPilot.backend.DTO.RepositoryResponse;
import devPilot.backend.security.CurrentUser;
import devPilot.backend.services.RepoService;
import devPilot.backend.services.indexing.IndexingService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/repos")
@RequiredArgsConstructor
public class RepoController {

    private final CurrentUser currentUser;
    private final RepoService repoService;
    private final IndexingService indexingService;

    @GetMapping
    public List<RepositoryResponse> list(
            @RequestParam(name = "refresh", defaultValue = "true") boolean refresh) {

        UUID userId = currentUser.require().getId();

        if (refresh) {
            return repoService.syncAndListRepos(userId);
        }

        return repoService.listSorted(userId);
    }

    @GetMapping("/{id}")
    public RepositoryResponse get(@PathVariable UUID id) {
        UUID userId = currentUser.require().getId();
        return repoService.toResponse(repoService.requireOwned(id, userId));
    }

    @GetMapping("/{id}/status")
    public IndexStatusResponse status(@PathVariable UUID id) {
        UUID userId = currentUser.require().getId();
        return repoService.status(id, userId);
    }

    @PostMapping("/{id}/index")
    public void startIndexing(@PathVariable UUID id) {
        UUID userId = currentUser.require().getId();
        indexingService.startIndexing(id, userId);
        indexingService.indexAsync(id, userId);
    }

    @PostMapping("/{id}/index/cancel")
    public void cancelIndexing(@PathVariable UUID id) {
        UUID userId = currentUser.require().getId();
        indexingService.cancelIndexing(id, userId);
    }
}
