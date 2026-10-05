package com.byy.ticket.event.dto.event;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
/** 一次提交完整草稿；初始化参数不提供修改入口，重试沿用创建幂等键。 */
public record EventDraftDTO(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String idempotencyKey,
        @NotBlank @Size(max=200) String name,@NotBlank @Size(max=30) String category,
        @Size(max=1024) String coverUrl,@Size(max=10000) String description,
        @NotEmpty @Size(max=20) List<@NotNull @Valid Session> sessions) {
    public record Session(@NotBlank @Size(max=100) String name,@NotBlank @Size(max=200) String venueName,
            @NotBlank @Size(max=500) String venueAddress,@NotNull LocalDateTime startTime,@NotNull LocalDateTime endTime,
            @NotNull LocalDateTime saleStartTime,@NotNull LocalDateTime saleEndTime,@NotNull @Positive Integer purchaseLimit,
            @NotEmpty @Size(max=20) List<@NotNull @Valid Tier> ticketTiers) { }
    public record Tier(@NotBlank @Size(max=100) String name,@NotNull @DecimalMin("0.01") @Digits(integer=8,fraction=2) BigDecimal price,
                       @NotNull @Positive Integer totalQuantity) { }
}
