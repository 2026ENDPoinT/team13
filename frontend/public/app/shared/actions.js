export const actions = {
  "save-post"(node, api) {
    const state = api.getState();
    const data = api.readForm();
    const errors = {};
    if (!String(data.title || "").trim()) errors.title = "제목을 입력하세요";
    if (!String(data.body || "").trim() || String(data.body).trim().length < 4) errors.body = "내용을 4자 이상 입력하세요";
    if (Object.keys(errors).length) return api.showErrors(errors);
    const board = node.dataset.board;
    const post = { id: `p-${Date.now()}`, title: data.title.trim(), body: data.body.trim(), author: "박민준", at: api.stamp(), board, reply: "" };
    if (board === "community") state.communityQna.unshift(post);
    else state.posts.unshift(post);
    state.toast = "등록했습니다";
    api.save();
    api.closeDialog();
  },
};
