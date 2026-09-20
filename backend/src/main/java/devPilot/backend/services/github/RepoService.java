package devPilot.backend.services.github;

import devPilot.backend.DTO.RepositoryResponse;
import devPilot.backend.model.Repository;
import devPilot.backend.model.User;
import devPilot.backend.repository.RepoRepository;
import devPilot.backend.services.UserService;
import lombok.AllArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;


@Service
@AllArgsConstructor
public class RepoService {

        @Autowired
        private final RepoRepository repoRepository;

        @Autowired
        private final UserService userService;

        @Autowired
        private final GithubApiClient githubApiClient;


        @Transactional
        public List<RepositoryResponse> syncAndListRepo(UUID userId){
            User user = userService.requiredById(userId);
            String token = userService.decryptAccessToken(user);
            List<Map<String,Object>> remoteRepos = githubApiClient.listUserRepos(token);

            List<Repository> saved = new ArrayList<>();

            for (Map<String, Object> remote : remoteRepos) {

                Long githubRepoId = toLong(remote.get("id"));

                Repository repo = repoRepository
                        .findByUserIdAndGithubRepoId(userId, githubRepoId)
                        .orElseGet(Repository::new);

                String fullName = String.valueOf(remote.get("full_name"));
                String[] parts = fullName.split("/", 2);

                repo.setUserId(userId);
                repo.setGithubRepoId(githubRepoId);

                repo.setOwner(
                        parts.length > 0
                                ? parts[0]
                                : String.valueOf(remote.get("owner"))
                );

                repo.setName(
                        parts.length > 1
                                ? parts[1]
                                : String.valueOf(remote.get("name"))
                );

                repo.setFullName(fullName);

                repo.setPrivate(
                        Boolean.TRUE.equals(remote.get("private"))
                );

                repo.setDefaultBranch(
                        remote.get("default_branch") != null
                                ? String.valueOf(remote.get("default_branch"))
                                : "main"
                );

                repo.setLanguage(
                        remote.get("language") != null
                                ? String.valueOf(remote.get("language"))
                                : null
                );

                repo.setHtmlUrl(
                        remote.get("html_url") != null
                                ? String.valueOf(remote.get("html_url"))
                                : null
                );

                repo.setDescription(
                        remote.get("description") != null
                                ? String.valueOf(remote.get("description"))
                                : null
                );

                repo.setUpdatedAt(Instant.now());

                if (repo.getOwner() == null || repo.getOwner().isBlank()) {

                    Object ownerObj = remote.get("owner");

                    if (ownerObj instanceof Map<?, ?> ownerMap
                            && ownerMap.get("login") != null) {

                        repo.setOwner(
                                String.valueOf(ownerMap.get("login"))
                        );
                    }
                }

                saved.add(repoRepository.save(repo));
            }

            return saved.stream()
                    .sorted((a,b) -> a.getFullName().compareToIgnoreCase(b.getFullName()))
                    .map(this::toResponse)
                    .toList();

        }

        public RepositoryResponse toResponse(Repository repo) {
            return new RepositoryResponse(
                repo.getId(),
                repo.getGithubRepoId(),
                repo.getOwner(),
                repo.getName(),
                repo.getFullName(),
                repo.isPrivate(),
                repo.getDefaultBranch(),
                repo.getLanguage(),
                repo.getHtmlUrl(),
                repo.getDescription(),
                repo.getIndexStatus(),
                repo.getIndexedAt(),
                repo.getChunkCount(),
                repo.getFilesTotal(),
                repo.getFilesProcessed(),
                repo.getErrorMessage()
        );
    }

        @Transactional(readOnly = true)
        public List<RepositoryResponse> listSorted(UUID userId){
            return repoRepository.findByUserIdOrderByFullNameAsc(userId).stream()
                    .map(this::toResponse)
                    .toList();
        }



        private static Long toLong(Object value){
            if(value instanceof Number number){
                return number.longValue();
            }
            return Long.parseLong(String.valueOf(value));
        }


}
