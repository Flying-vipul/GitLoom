package devPilot.backend.services.AI;

import devPilot.backend.DTO.CitationDto;

import java.util.List;

public record RetrievedContext(
        List<CitationDto> citations,
        String contextText) {

}
