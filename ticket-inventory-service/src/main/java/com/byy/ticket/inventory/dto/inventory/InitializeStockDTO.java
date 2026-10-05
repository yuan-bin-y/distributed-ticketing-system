package com.byy.ticket.inventory.dto.inventory;
import jakarta.validation.constraints.*;
/** 稳定初始化参数；重复请求不得更改场次、数量或重置可用库存。 */
public record InitializeStockDTO(@NotNull @Positive Long ticketTierId,@NotNull @Positive Long sessionId,
                                 @NotNull @Positive Integer totalQuantity) { }
