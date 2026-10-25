/* 页面只负责交互：座位提示不能替代服务端预扣、累计限购和订单状态判断。 */
const $ = (id) => document.getElementById(id);
const state = {
  access: "",
  refresh: "",
  event: null,
  session: null,
  tier: null,
  layout: null,
  selected: new Set(),
  seats: new Map(),
  window: "",
  version: 0,
  fetching: false,
  generation: 0,
  eventPage: 1,
  sessionAfter: 0,
  tierAfter: 0,
  pending: null,
  requestId: null,
  orderNo: null,
  poll: null,
};
const money = (value) => "¥ " + Number(value || 0).toFixed(2);
function notice(message = "") {
  $("notice").textContent = message;
}
async function api(path, body, method = body ? "POST" : "GET", retry = true) {
  const response = await fetch(path, {
    method,
    headers: {
      ...(body ? { "Content-Type": "application/json" } : {}),
      ...(state.access ? { Authorization: "Bearer " + state.access } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
    signal: AbortSignal.timeout(12000),
  });
  if (
    response.status === 401 &&
    state.refresh &&
    retry &&
    !path.includes("/api/auth/")
  ) {
    const token = await api(
      "/api/auth/refresh",
      { refreshToken: state.refresh },
      "POST",
      false,
    );
    state.access = token.accessToken;
    state.refresh = token.refreshToken;
    return api(path, body, method, false);
  }
  const result = await response.json();
  if (!response.ok || result.code !== "OK") {
    const error = new Error(result.message || "请求暂未成功，请稍后重试");
    error.status = response.status;
    throw error;
  }
  return result.data;
}
function option(select, value, label) {
  const item = document.createElement("option");
  item.value = value;
  item.textContent = label;
  select.append(item);
}
function empty(select, label) {
  select.replaceChildren();
  option(select, "", label);
  select.disabled = true;
}
async function events(more = false) {
  try {
    const page = await api(
      "/api/events?page=" +
        (more ? ++state.eventPage : (state.eventPage = 1)) +
        "&pageSize=20",
    );
    if (!more) $("event").replaceChildren();
    for (const event of page.records) option($("event"), event.id, event.name);
    $("more-events").hidden = page.page * page.pageSize >= page.total;
    if (!page.records.length && !more) {
      option($("event"), "", "暂无已发布活动");
      notice("目前还没有已发布活动，请先通过管理接口创建并发布活动。");
      return;
    }
    if (!more) await chooseEvent();
  } catch (error) {
    notice(error.message);
  }
}
async function chooseEvent(more = false) {
  if (!more) {
    state.event = $("event").value;
    state.sessionAfter = 0;
    empty($("session"), "正在加载场次");
    empty($("tier"), "请先选择场次");
    clearSeats();
  }
  if (!state.event) return;
  try {
    const page = await api(
      `/api/events/${state.event}/sessions/page?after=${state.sessionAfter}&limit=20`,
    );
    if (!more) $("session").replaceChildren();
    for (const session of page.records)
      option(
        $("session"),
        session.id,
        session.name + " · " + session.startTime.replace("T", " ").slice(0, 16),
      );
    state.sessionAfter = page.nextCursor;
    $("more-sessions").hidden = !page.hasMore;
    $("session").disabled = !$("session").options.length;
    if (!more && page.records.length) await chooseSession();
  } catch (error) {
    notice(error.message);
  }
}
async function chooseSession(more = false) {
  if (!more) {
    state.session = $("session").value;
    state.tierAfter = 0;
    empty($("tier"), "正在加载票档");
    clearSeats();
  }
  if (!state.session) return;
  try {
    const page = await api(
      `/api/events/${state.event}/sessions/${state.session}/ticket-tiers/page?after=${state.tierAfter}&limit=20`,
    );
    if (!more) $("tier").replaceChildren();
    for (const tier of page.records) {
      option($("tier"), tier.id, tier.name + " / " + money(tier.price));
      $("tier").lastChild.dataset.price = tier.price;
    }
    state.tierAfter = page.nextCursor;
    $("more-tiers").hidden = !page.hasMore;
    $("tier").disabled = !$("tier").options.length;
    if (!more && page.records.length) await chooseTier();
  } catch (error) {
    notice(error.message);
  }
}
function clearSeats() {
  state.generation++;
  state.selected.clear();
  state.seats.clear();
  state.window = "";
  state.version = 0;
  state.layout = null;
  state.tier = null;
  $("seat-canvas").replaceChildren();
  $("seat-canvas").style.height = "100%";
  $("seat-canvas").style.width = "100%";
  $("seat-empty").hidden = false;
  $("region").disabled = true;
  updateCart();
}
async function chooseTier() {
  clearSeats();
  state.tier = $("tier").value;
  if (!state.tier) return;
  const generation = state.generation;
  try {
    const layout = await api(
      `/api/events/${state.event}/ticket-tiers/${state.tier}/seat-layout`,
    );
    if (generation !== state.generation) return;
    state.layout = layout;
    $("region").replaceChildren();
    for (const region of layout.regions)
      option($("region"), region.code, region.code + " 区");
    $("region").disabled = false;
    $("quantity-wrap").hidden = true;
    chooseRegion();
  } catch (error) {
    if (generation !== state.generation) return;
    if (error.status === 404) {
      $("quantity-wrap").hidden = false;
      $("seat-empty").textContent = "此票档为普通票，无需选座";
      $("seat-title").textContent = "无需选座，自在入场";
    } else notice(error.message);
  }
  updateCart();
}
function chooseRegion() {
  state.generation++;
  state.window = "";
  state.version = 0;
  state.seats.clear();
  const region = currentRegion();
  if (!region) return;
  $("seat-title").textContent = region.code + " 区 · 选择你的位置";
  $("seat-empty").hidden = true;
  $("seat-view").scrollTop = 0;
  $("seat-view").scrollLeft = 0;
  $("seat-canvas").style.height = region.rows * 36 + 12 + "px";
  $("seat-canvas").style.width = region.columns * 36 + 42 + "px";
  if (!state.access) $("seat-hint").textContent = "登录后查看可选座位";
  renderSeats();
  refreshSeats().catch((error) => notice(error.message));
}
function currentRegion() {
  return state.layout?.regions.find((item) => item.code === $("region").value);
}
function visibleWindow() {
  const region = currentRegion(),
    view = $("seat-view");
  if (!region) return null;
  const rowStart = Math.max(1, Math.floor(view.scrollTop / 36)),
    columnStart = Math.max(1, Math.floor(view.scrollLeft / 36));
  return {
    region: region.code,
    rowStart,
    rowCount: Math.min(
      20,
      region.rows - rowStart + 1,
      Math.ceil(view.clientHeight / 36) + 2,
    ),
    columnStart,
    columnCount: Math.min(
      50,
      region.columns - columnStart + 1,
      Math.ceil(view.clientWidth / 36) + 2,
    ),
  };
}
function renderSeats() {
  const region = currentRegion(),
    window = visibleWindow();
  if (!region || !window) return;
  const fragment = document.createDocumentFragment();
  for (
    let row = window.rowStart;
    row < window.rowStart + window.rowCount;
    row++
  ) {
    const label = document.createElement("span");
    label.className = "seat-row";
    label.style.top = (row - 1) * 36 + 7 + "px";
    label.textContent = row;
    fragment.append(label);
    for (
      let col = window.columnStart;
      col < window.columnStart + window.columnCount;
      col++
    ) {
      const id = `${region.code}:${row}:${col}`,
        status = state.seats.get(id),
        selected = state.selected.has(id),
        button = document.createElement("button");
      button.type = "button";
      button.className =
        "seat" +
        (selected
          ? " selected"
          : status && status !== "AVAILABLE"
            ? " taken"
            : !status
              ? " unknown"
              : "");
      button.style.top = (row - 1) * 36 + 7 + "px";
      button.style.left = (col - 1) * 36 + 36 + "px";
      button.textContent = col;
      button.dataset.seat = id;
      button.setAttribute(
        "aria-label",
        `${region.code}区 ${row}排 ${col}座 ${selected ? "已选" : status === "AVAILABLE" ? "可选" : status ? "已占用" : "待加载"}`,
      );
      button.setAttribute("aria-pressed", selected);
      button.disabled =
        !!state.pending || (!selected && status !== "AVAILABLE");
      button.onclick = () => {
        if (state.selected.has(id)) state.selected.delete(id);
        else if (state.selected.size < 100) state.selected.add(id);
        renderSeats();
        updateCart();
      };
      fragment.append(button);
    }
  }
  $("seat-canvas").replaceChildren(fragment);
}
async function refreshSeats() {
  if (!state.access || !state.layout || state.fetching || document.hidden)
    return;
  state.fetching = true;
  const generation = state.generation,
    window = visibleWindow(),
    key = JSON.stringify(window);
  let since = key === state.window ? state.version : 0,
    after = 0,
    through = 0;
  try {
    let more;
    do {
      const params = new URLSearchParams({
        ...window,
        since,
        after,
        through,
        limit: 200,
      });
      const page = await api(
        `/api/orders/ticket-tiers/${state.tier}/seats?${params}`,
      );
      if (generation !== state.generation) return;
      for (const seat of page.records)
        state.seats.set(seat.seatId, seat.status);
      through = page.version;
      after = page.nextAfter;
      more = page.hasMore;
    } while (more);
    state.window = key;
    state.version = through;
    for (const selected of [...state.selected])
      if (
        state.seats.has(selected) &&
        state.seats.get(selected) !== "AVAILABLE" &&
        !state.pending
      )
        state.selected.delete(selected);
    renderSeats();
    updateCart();
    $("seat-hint").textContent = "座位状态已更新";
  } finally {
    state.fetching = false;
  }
}
function updateCart() {
  const tier = $("tier").selectedOptions[0],
    quantity = state.layout
      ? state.selected.size
      : Number($("quantity").value || 0);
  $("cart-show").textContent = state.tier
    ? [
        $("event").selectedOptions[0]?.textContent,
        $("session").selectedOptions[0]?.textContent,
        tier?.textContent,
      ]
        .filter(Boolean)
        .join(" / ")
    : "尚未选择演出";
  $("total").textContent = money(Number(tier?.dataset.price || 0) * quantity);
  $("selected-seats").replaceChildren();
  if (state.selected.size)
    for (const id of state.selected) {
      const tag = document.createElement("span");
      tag.className = "tag";
      tag.textContent = id.replaceAll(":", " · ");
      $("selected-seats").append(tag);
    }
  else {
    const hint = document.createElement("span");
    hint.className = "muted";
    hint.textContent = state.layout
      ? "从左侧座位图选择"
      : "普通票 / " + quantity + " 张";
    $("selected-seats").append(hint);
  }
  $("purchase").disabled = !state.tier || quantity < 1 || !!state.requestId;
}
function showProgress(text) {
  $("progress").hidden = false;
  $("progress").textContent = text;
}
function lockBooking(locked) {
  for (const id of [
    "event",
    "session",
    "tier",
    "region",
    "quantity",
    "more-events",
    "more-sessions",
    "more-tiers",
  ])
    $(id).disabled = locked || (id === "region" && !state.layout);
  renderSeats();
}
async function purchase() {
  if (!state.access) {
    $("auth").showModal();
    return;
  }
  if (!state.pending)
    state.pending = {
      ticketTierId: Number(state.tier),
      quantity: state.layout
        ? state.selected.size
        : Number($("quantity").value),
      idempotencyKey: crypto.randomUUID().replaceAll("-", ""),
      seatIds: [...state.selected].sort(),
    };
  $("purchase").disabled = true;
  notice();
  try {
    const accepted = await api("/api/orders/requests", state.pending);
    state.requestId = accepted.requestId;
    showProgress("正在确认你的购票请求，请稍候…");
    renderSeats();
    await trackOrder();
  } catch (error) {
    if ([400, 403, 404, 409].includes(error.status)) {
      // 服务端明确拒绝受理时可以调整选择；超时和5xx仍保留原请求。
      state.pending = null;
      lockBooking(false);
      updateCart();
      notice(error.message);
      return;
    }
    notice(error.message + "。如结果未知，请保持原选择重试。");
    $("purchase").disabled = false;
  }
}
async function trackOrder() {
  clearTimeout(state.poll);
  try {
    if (!state.orderNo) {
      const accepted = await api("/api/orders/requests/" + state.requestId);
      if (
        accepted.status === "REJECTED" ||
        accepted.status === "REVIEW_REQUIRED"
      ) {
        showProgress(accepted.message || "本次购票未完成，请重新选票");
        $("reset").hidden = false;
        return;
      }
      state.orderNo = accepted.orderNo;
    }
    if (state.orderNo) {
      const order = await api("/api/orders/" + state.orderNo);
      const names = {
        STOCK_PENDING: "正在确认库存",
        PENDING_PAYMENT:
          "座位已确认，请在 " + order.expiresAt.replace("T", " ") + " 前付款",
        PAYMENT_CONFIRMING: "正在确认付款",
        PAID: "付款成功，正在出票",
        COMPLETED: "购票完成，期待与你在现场相见。",
        CREATE_FAILED: "本次未能完成购票",
        CLOSED: "订单已关闭",
        REVERSED: "模拟付款已冲正",
        REVIEW_REQUIRED: "订单需要核对，请稍后查询",
      };
      showProgress(names[order.status] || "订单正在处理中");
      $("pay").hidden = order.status !== "PENDING_PAYMENT";
      if (order.status === "COMPLETED") {
        const result = await api("/api/orders/" + state.orderNo + "/tickets");
        $("tickets").replaceChildren();
        for (const ticket of result.tickets) {
          const card = document.createElement("div");
          card.className = "ticket";
          const title = document.createElement("strong");
          title.textContent = ticket.seatId
            ? ticket.seatId.replaceAll(":", " · ")
            : "普通入场票";
          const code = document.createElement("code");
          code.textContent = ticket.ticketNo;
          card.append(title, document.createElement("br"), code);
          $("tickets").append(card);
        }
        $("reset").hidden = false;
        return;
      }
      if (
        ["CREATE_FAILED", "CLOSED", "REVERSED", "REVIEW_REQUIRED"].includes(
          order.status,
        )
      ) {
        $("reset").hidden = false;
        return;
      }
    }
  } catch (error) {
    notice(error.message);
  }
  state.poll = setTimeout(trackOrder, 2000);
}
$("account").onclick = () => {
  $("auth").showModal();
};
$("close-auth").onclick = () => $("auth").close();
async function login(register = false) {
  $("auth-error").textContent = "";
  const body = { username: $("username").value, password: $("password").value };
  try {
    if (register)
      await api("/api/auth/register", { ...body, nickname: body.username });
    const result = await api("/api/auth/login", body);
    state.access = result.accessToken;
    state.refresh = result.refreshToken;
    $("account").textContent = body.username;
    $("password").value = "";
    $("auth").close();
    notice();
    await refreshSeats().catch((error) => notice(error.message));
  } catch (error) {
    $("auth-error").textContent = error.message;
  }
}
$("login-form").onsubmit = (event) => {
  event.preventDefault();
  login();
};
$("register").onclick = () => {
  if ($("login-form").reportValidity()) login(true);
};
$("event").onchange = () => chooseEvent();
$("session").onchange = () => chooseSession();
$("tier").onchange = chooseTier;
$("region").onchange = chooseRegion;
$("more-events").onclick = () => events(true);
$("more-sessions").onclick = () => chooseEvent(true);
$("more-tiers").onclick = () => chooseSession(true);
$("quantity").oninput = () => {
  state.pending = null;
  updateCart();
};
$("purchase").onclick = () => {
  purchase();
  if (state.pending) lockBooking(true);
};
$("pay").onclick = async () => {
  $("pay").disabled = true;
  try {
    const payment = await api("/api/orders/" + state.orderNo + "/payments", {});
    await api("/api/payments/" + payment.paymentNo + "/simulate-success", {});
    $("pay").hidden = true;
    showProgress("模拟付款成功，正在确认电子票…");
    trackOrder();
  } catch (error) {
    notice(error.message);
  } finally {
    $("pay").disabled = false;
  }
};
$("reset").onclick = () => {
  clearTimeout(state.poll);
  state.pending = null;
  state.requestId = null;
  state.orderNo = null;
  state.selected.clear();
  $("progress").hidden = true;
  $("tickets").replaceChildren();
  $("reset").hidden = true;
  lockBooking(false);
  state.version = 0;
  refreshSeats().catch((error) => notice(error.message));
  updateCart();
};
let scrollTimer;
$("seat-view").onscroll = () => {
  renderSeats();
  clearTimeout(scrollTimer);
  scrollTimer = setTimeout(
    () => refreshSeats().catch((error) => notice(error.message)),
    300,
  );
};
setInterval(
  () =>
    refreshSeats().catch((error) => {
      $("seat-hint").textContent = "座位状态稍后更新";
    }),
  4000,
);
window.addEventListener("resize", renderSeats);
events();
