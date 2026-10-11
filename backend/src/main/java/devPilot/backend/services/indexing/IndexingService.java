package devPilot.backend.services.indexing;

import devPilot.backend.exceptions.BadRequestException;
import devPilot.backend.exceptions.NotFoundException;
import devPilot.backend.model.IndexStatus;
import devPilot.backend.model.Repository;
import devPilot.backend.repository.RepoRepository;
import devPilot.backend.services.AI.RagSettings;
import devPilot.backend.services.UserService;
import devPilot.backend.services.github.GithubApiClient;
import devPilot.backend.services.github.GithubRateLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class IndexingService {

    private static final int VECTOR_BATCH_SIZE = 32;
    private static final int PROGRESS_EVERY_N_FILES = 5;

    private final RepoRepository repoRepository;
    private final UserService userService;
    private final GithubApiClient githubApiClient;
    private final CodeFileFilter fileFilter;
    private final CodeChunker codeChunker;
    private final GithubRateLimiter rateLimiter;
    private final VectorStore vectorStore;

    @Value("${app.indexing.max-file-bytes:102400}")
    private long maxFileBytes;

    @Transactional
    public void cancelIndexing(UUID repoId, UUID userId) {
        Repository repo = repoRepository.findByIdAndUserId(repoId, userId).orElseThrow(() -> new NotFoundException("Not found"));
        if (repo.getIndexStatus() == IndexStatus.INDEXING) {
            repo.setIndexStatus(IndexStatus.FAILED);
            repo.setErrorMessage("Cancelled by user");
            repoRepository.save(repo);
        }
    }

    public Repository startIndexing(UUID repoId, UUID userId) {
        Repository repo = repoRepository.findByIdAndUserId(repoId, userId)
                .orElseThrow(() -> new NotFoundException("Repository not found"));

        if (repo.getIndexStatus() == IndexStatus.INDEXING) {
            throw new BadRequestException("Repository is already being indexed");
        }

        repo.setIndexStatus(IndexStatus.INDEXING);
        repo.setFilesProcessed(0);
        repo.setFilesTotal(0);
        repo.setChunkCount(0);
        repo.setErrorMessage(null);
        repo.setUpdatedAt(Instant.now());

        return repoRepository.save(repo);
    }


    @Async("indexingExecutor")
    public void indexAsync(UUID repoId, UUID userId) {
        try {
            doIndex(repoId, userId);
        } catch (Exception ex) {
            log.error("Indexing failed for repo {}", repoId, ex);
            markFailed(repoId, ex.getMessage());
        }
    }

    private void doIndex(UUID repoId, UUID userId) {
        Repository repo = repoRepository.findById(repoId)
                .orElseThrow(() -> new NotFoundException("Repository not found"));

        String token = userService.decryptAccessToken(
                userService.requiredById(userId)
        );

        deleteExistingVectors(repoId.toString());

        Map<String, Object> tree = githubApiClient.getRepoTree(
                token,
                repo.getOwner(),
                repo.getName(),
                repo.getDefaultBranch()
        );

        List<String> filePaths = listIndexableFiles(tree);

        updateProgress(
                repoId,
                filePaths.size(),
                0,
                0,
                IndexStatus.INDEXING,
                null
        );

        List<Document> batch = new ArrayList<>();
        int processed = 0;
        int totalChunks = 0;

        for (String path : filePaths) {
            try {
                String content = githubApiClient.getFileContent(
                        token,
                        repo.getOwner(),
                        repo.getName(),
                        path
                );

                List<Document> chunks = codeChunker.chunkFile(
                        repoId.toString(),
                        path,
                        content
                );

                batch.addAll(chunks);
                totalChunks += chunks.size();

                if (batch.size() >= VECTOR_BATCH_SIZE) {
                    vectorStore.add(batch);
                    batch.clear();
                }

            } catch (Exception ex) {
                log.warn(
                        "Skipping file {} in {}: {}",
                        path,
                        repo.getFullName(),
                        ex.getMessage()
                );
            }

            processed++;

            if (processed % PROGRESS_EVERY_N_FILES == 0
                    || processed == filePaths.size()) {

                updateProgress(
                        repoId,
                        filePaths.size(),
                        processed,
                        totalChunks,
                        IndexStatus.INDEXING,
                        null
                );
            }

            rateLimiter.pause();
            Repository current = repoRepository.findById(repoId).orElse(null);
            if (current != null && current.getIndexStatus() != IndexStatus.INDEXING) {
                log.info("Indexing cancelled for {}", repoId);
                return;
            }
        }

        if (!batch.isEmpty()) {
            vectorStore.add(batch);
        }

        markReady(
                repoId,
                filePaths.size(),
                processed,
                totalChunks,
                repo.getFullName()
        );
    }

    @SuppressWarnings("unchecked")
    private List<String> listIndexableFiles(Map<String, Object> tree) {
        if (tree == null || tree.get("tree") == null) {
            return List.of();
        }

        List<Map<String, Object>> entries =
                (List<Map<String, Object>>) tree.get("tree");

        return entries.stream()
                .filter(entry ->
                        "blob".equals(String.valueOf(entry.get("type"))))
                .filter(entry -> {
                    String path = String.valueOf(entry.get("path"));

                    long size = entry.get("size") instanceof Number n
                            ? n.longValue()
                            : 0L;

                    return fileFilter.isEligible(
                            path,
                            size,
                            maxFileBytes
                    );
                })
                .map(entry ->
                        String.valueOf(entry.get("path")))
                .toList();
    }

    private void deleteExistingVectors(String repoId) {
        try {
            var filter = new FilterExpressionBuilder()
                    .eq(RagSettings.METADATA_REPO_ID, repoId)
                    .build();

            vectorStore.delete(filter);

        } catch (Exception ex) {
            log.warn(
                    "Could not delete existing vectors for repo {}: {}:",
                    repoId,
                    ex.getMessage()
            );
        }
    }

    @Transactional
    protected void updateProgress(
            UUID repoId,
            int total,
            int processed,
            int chunks,
            IndexStatus status,
            String error
    ) {
        repoRepository.findById(repoId).ifPresent(repo -> {
            repo.setFilesTotal(total);
            repo.setFilesProcessed(processed);
            repo.setChunkCount(chunks);
            repo.setIndexStatus(status);
            repo.setErrorMessage(error);
            repo.setUpdatedAt(Instant.now());

            repoRepository.save(repo);
        });
    }

    @Transactional
    protected void markReady(
            UUID repoId,
            int totalFiles,
            int processedFiles,
            int totalChunks,
            String fullName
    ) {
        repoRepository.findById(repoId).ifPresent(repo -> {
            repo.setIndexStatus(IndexStatus.READY);
            repo.setFilesTotal(totalFiles);
            repo.setFilesProcessed(processedFiles);
            repo.setChunkCount(totalChunks);
            repo.setIndexedAt(Instant.now());
            repo.setErrorMessage(null);
            repo.setUpdatedAt(Instant.now());

            repoRepository.save(repo);
        });

        log.info(
                "Indexed {} files ({} chunks) for {}",
                processedFiles,
                totalChunks,
                fullName
        );
    }

    @Transactional
    protected void markFailed(UUID repoId,String message){
        repoRepository.findById(repoId).ifPresent(repo -> {
            repo.setIndexStatus(IndexStatus.FAILED);
            repo.setErrorMessage(message != null && message.length() > 2000
                    ? message.substring(0,2000)
                    : message);
            repo.setUpdatedAt(Instant.now());
            repoRepository.save(repo);
        });
    }
}
