package devPilot.backend.DTO;


import jakarta.validation.constraints.NotBlank;

public record ChatMessageRequest(
         @NotBlank String content
) {


}
