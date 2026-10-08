// IslandBridge network hook — forwards Doubao SSE stream chunks in real time.
// Reports via window.__caplog(jsonString) (CDP Runtime binding).
//
// v2: installs via Object.defineProperty so later page code that re-assigns
// window.fetch / XHR.prototype methods gets wrapped instead of overwriting us.
// (Found empirically: Doubao re-wraps fetch after injection, killing a plain
// assignment hook — 0 events reached the daemon.)
//
// v5 (uplink): adds the *real* uplink interfaces instead of only replaying a
// captured /chat/completion template —
//   window.__ibSend(cid, text)     POST the app's own send request
//                                  (/chat/completion on the desktop client,
//                                  /im/sse/send/message when that is what the
//                                  app uses), replayed in-page so cookies and
//                                  the app's signing layer (msToken/a_bogus,
//                                  applied below window.fetch) still apply.
//   window.__ibDelete(cid,botId,candidates)
//                                  POST the real conversation-delete request.
//                                  The JSON envelope (cmd + uplink_body) is
//                                  built by bridge_daemon.py from the verified
//                                  protocol; only the transport lives here.
// window.__ibSendReply is kept as an alias for older daemons.
(function () {
  if (window.__islandhook_v4) return 'already';
  window.__islandhook_v4 = true;
  const S = (o) => { try { return JSON.stringify(o); } catch (e) { return '{"err":"json"}'; } };
  const emit = (o) => { try { window.__caplog && window.__caplog(S(o)); } catch (e) {} };
  const b64 = (buf) => {
    try {
      let u8 = buf instanceof Uint8Array ? buf : new Uint8Array(buf);
      let s = ''; const CH = 0x8000;
      for (let i = 0; i < u8.length; i += CH)
        s += String.fromCharCode.apply(null, u8.subarray(i, i + CH));
      return btoa(s);
    } catch (e) { return ''; }
  };

  // Endpoints we care about (substring match on url)
  const WATCH = [
    '/chat/completion',
    '/chat/async/chunk_stream',
    '/samantha/chat/async/stream',
    '/im/sse/send/message',
    '/im/send/message',
    '/im/conversation/del_user_conv',
    '/im/conversation/batch_del_user_conv',
    '/im/conversation/batch_operate',
    '/im/conversation/operate',
    '/im/conversation/info',
    '/im/conversation/abstract',
    '/im/thread/info',
    '/im/chain/thread_message',
    '/im/chain/single',
    '/samantha/supertask/terminate',
    '/samantha/supertask/reactivate',
  ];
  const watch = (url) => WATCH.some((p) => url.indexOf(p) >= 0);

  // Uplink request paths whose bodies are candidates for __ibSend replay.
  // Desktop client sends via /chat/completion (verified: 120 real requests in
  // capture/hook.jsonl); the IM SSE path is the Android client's interface
  // (REVERSE_NOTES §1) and is replayed the same way if the page ever uses it.
  const SEND_PATHS = ['/chat/completion', '/im/sse/send/message', '/im/send/message'];
  const isSendPath = (u) => SEND_PATHS.some((p) => u.indexOf(p) >= 0);

  // Remember the query string of the app's own /im/ calls: the delete
  // interface needs the same common params (device_id, aid, pc_version, …).
  const noteUrl = (url) => {
    try {
      const u = new URL(url, location.href);
      if (u.pathname.indexOf('/im/') === 0 || u.pathname.indexOf('/chat/') === 0) {
        window.__ibQuery = u.search || '';
        window.__ibOrigin = u.origin;
        if (u.pathname.indexOf('/im/') === 0) window.__ibImQuery = u.search || '';
      }
    } catch (e) {}
  };

  // ---------- guarded wrapper: survive later re-assignment ----------
  // wrap(realFn) -> instrumentedFn. The getter always returns the latest
  // instrumented version; if the app assigns a new function, we wrap THAT.
  function guard(obj, prop, wrap) {
    try {
      let wrapped = wrap(obj[prop]);
      const desc = Object.getOwnPropertyDescriptor(obj, prop) || {};
      Object.defineProperty(obj, prop, {
        configurable: true,
        enumerable: desc.enumerable !== undefined ? desc.enumerable : true,
        get() { return wrapped; },
        set(v) { wrapped = wrap(v); },
      });
      return true;
    } catch (e) { return false; }
  }

  // ---------- send-request template + programmatic reply ----------
  // The page signs requests below window.fetch (we see every send twice:
  // unsigned first, then with msToken/a_bogus). Replaying through
  // window.fetch with the UNSIGNED url re-enters that same signing layer,
  // so __ibSend = template clone + fresh ids + new text.
  const normHeaders = (h) => {
    try {
      if (!h) return null;
      if (h instanceof Headers) {
        const o = {}; h.forEach((v, k) => { o[k] = v; }); return o;
      }
      if (Array.isArray(h)) {
        const o = {}; for (const kv of h) o[kv[0]] = kv[1]; return o;
      }
      return { ...h };
    } catch (e) { return null; }
  };
  window.__ibSendByCid = window.__ibSendByCid || {};
  const ibUuid = () => 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'
    .replace(/[xy]/g, (c) => {
      const r = Math.random() * 16 | 0;
      return (c === 'x' ? r : (r & 3 | 8)).toString(16);
    });

  // Conversation id of a send body, whichever envelope shape it uses.
  const bodyCid = (o) => {
    try {
      const cm = o.client_meta;
      if (cm && (cm.conversation_id || cm.local_conversation_id))
        return cm.conversation_id || cm.local_conversation_id || '';
      const ub = o.uplink_body || {};
      for (const k in ub) {
        const b = ub[k] || {};
        const c2 = b.client_meta || b.clientMeta;
        if (c2 && (c2.conversation_id || c2.local_conversation_id))
          return c2.conversation_id || c2.local_conversation_id || '';
        if (b.conversation_id) return b.conversation_id;
      }
    } catch (e) {}
    return '';
  };

  function stashSend(url, method, headers, body) {
    try {
      const o = JSON.parse(body);
      const cid = bodyCid(o);
      const t = { url, method, headers, body, t: Date.now() };
      window.__ibSendLast = t;
      if (cid) window.__ibSendByCid[cid] = t;
    } catch (e) {}
  }

  // ---------- body surgery ----------
  // Rewrites only fields named in REVERSE_NOTES (block text + the fresh-id
  // fields the real call chain fills in). Never invents new fields.
  function swapIds(o) {
    const seen = new Set();
    const walk = (n) => {
      if (!n || typeof n !== 'object' || seen.has(n)) return;
      seen.add(n);
      if (Array.isArray(n)) { n.forEach(walk); return; }
      for (const k in n) {
        const v = n[k];
        if (k === 'local_message_id' || k === 'block_id' || k === 'unique_key')
          n[k] = ibUuid();
        else if (k === 'sequence_id') n[k] = ibUuid();
        else if (k === 'create_time_ms') n[k] = Date.now();
        else walk(v);
      }
    };
    walk(o);
    try {
      if (o.option && o.option.create_time_ms === undefined)
        o.option.create_time_ms = Date.now();
      if (o.option && o.option.recovery_option)
        o.option.recovery_option.req_create_time_sec = (Date.now() / 1000) | 0;
    } catch (e) {}
  }

  function setSendText(o, cid, text) {
    let n = 0;
    const put = (bl) => {
      const tb = bl && bl.content && bl.content.text_block;
      if (tb) { tb.text = text; n++; }
    };
    try {
      if (o.client_meta && cid) o.client_meta.conversation_id = cid;
      // /chat/completion shape: messages[].content_block[].content.text_block
      if (Array.isArray(o.messages)) {
        o.messages.forEach((m) => (m.content_block || []).forEach(put));
      }
      // IM SSE shape: uplink_body.send_message_body.request_messages[…]
      const ub = o.uplink_body || {};
      for (const k in ub) {
        const b = ub[k] || {};
        const rm = b.request_messages || b.requestMessages;
        if (Array.isArray(rm)) rm.forEach((m) => (m.content_block || []).forEach(put));
      }
    } catch (e) {}
    return n;
  }

  // daemon calls this via Runtime.evaluate(awaitPromise) on POST /reply.
  window.__ibSend = function (cid, text) {
    try {
      const t = (cid && window.__ibSendByCid[cid]) || window.__ibSendLast;
      if (!t) return Promise.resolve({ ok: false, err: 'no-template' });
      const b = JSON.parse(t.body);
      swapIds(b);
      const n = setSendText(b, cid, text);
      if (!n) return Promise.resolve({ ok: false, err: 'no-text-block' });
      const hd = (t.headers && Object.keys(t.headers).length) ? t.headers : null;
      const init = { method: t.method || 'POST',
                     credentials: 'include', body: JSON.stringify(b) };
      // captured headers are replayed verbatim; without them a string body
      // would go out as text/plain, so send the JSON content type ourselves
      init.headers = hd || { 'Content-Type': 'application/json' };
      noteUrl(t.url);
      return window.fetch(t.url, init).then(function (r) {
        return { ok: r.status < 400, status: r.status, via: t.url.split('?')[0] };
      }).catch(function (e) { return { ok: false, err: String(e) }; });
    } catch (e) {
      return Promise.resolve({ ok: false, err: String(e) });
    }
  };
  // older daemons call this name
  window.__ibSendReply = window.__ibSend;

  // POST a real delete request. `cands` is a JSON string:
  //   [{"path":"/im/conversation/del_user_conv","body":{...cmd 1121...}},
  //    {"path":"/im/conversation/batch_operate","body":{...cmd 1125...}}]
  // The envelope comes from bridge_daemon (dexdump-verified cmd/uplink_body
  // shapes, placeholder ids written as $CID / $BOTID); the transport stays
  // here so cookies + the app's signing layer apply, exactly like send.
  window.__ibDelete = function (cid, botId, cands) {
    let list;
    try { list = typeof cands === 'string' ? JSON.parse(cands) : (cands || []); }
    catch (e) { return Promise.resolve({ ok: false, err: 'bad-candidates' }); }
    if (!list.length) return Promise.resolve({ ok: false, err: 'no-candidates' });
    const origin = window.__ibOrigin || location.origin;
    const qs = window.__ibImQuery || window.__ibQuery || '';
    const fill = (n) => {
      if (!n || typeof n !== 'object') return;
      if (Array.isArray(n)) { n.forEach(fill); return; }
      for (const k in n) {
        const v = n[k];
        if (v === '$CID') n[k] = cid || '';
        else if (v === '$BOTID') n[k] = botId || '';
        else if (Array.isArray(v) && v.length === 1 && v[0] === '$CID')
          n[k] = cid ? [cid] : [];
        else fill(v);
      }
    };
    const attempt = (i) => {
      if (i >= list.length) {
        return Promise.resolve({ ok: false, err: 'all-candidates-failed' });
      }
      const c = list[i] || {};
      const body = JSON.parse(JSON.stringify(c.body || {}));
      fill(body);
      const url = origin + c.path + qs;
      noteUrl(url);
      return window.fetch(url, {
        method: 'POST', credentials: 'include', body: JSON.stringify(body),
        headers: { 'Content-Type': 'application/json; encoding=utf-8',
                   'Agw-Js-Conv': 'str' },
      }).then(function (r) {
        return r.text().then(function (t) {
          let o = {};
          try { o = JSON.parse(t); } catch (e) {}
          const db = o.downlink_body || {};
          const nested = db.del_user_conv_downlink_body
            || db.batch_operate_conv_downlink_body
            || db.batch_del_user_conv_downlink_body || {};
          const rc = o.status_code;
          const ok = (r.status < 400) && (rc === 0 || rc === '0')
            && nested.has_failure !== true;
          const res = { ok: ok, status: r.status, rc: rc,
                        desc: o.status_desc || '', via: c.path };
          if (!ok) res.err = 'HTTP ' + r.status + ' rc=' + rc + ' '
            + (o.status_desc || '');
          return res;
        });
      }).catch(function (e) {
        return { ok: false, err: String(e), via: c.path };
      }).then(function (res) {
        if (res.ok) return res;
        return attempt(i + 1).then(function (nx) {
          if (nx.ok) return nx;
          const joined = [res.err, nx.err].filter(Boolean).join(' | ');
          nx.err = joined || nx.err;
          nx.tried = (nx.tried || 1) + 1;
          return nx;
        });
      });
    };
    return attempt(0);
  };

  // ---------- fetch ----------
  function wrapFetch(real) {
    if (typeof real !== 'function' || real.__ib_wrapped) return real;
    const f = function (input, init) {
      const url = (typeof input === 'string') ? input : (input && input.url) || '';
      if (!watch(url)) return real.apply(this, arguments);

      const method = (init && init.method) || (input && input.method) || 'GET';
      const rec = { k: 'req', url, method, t: Date.now() };
      try {
        noteUrl(url);
        const b = init && init.body;
        if (typeof b === 'string') rec.reqBody = b.slice(0, 100000);
        const h = (init && init.headers) || (input && input.headers);
        const ho = normHeaders(h);
        if (ho) rec.reqHeaders = ho;
        // remember the send template for __ibSend (keep the PRE-SIGN call —
        // a_bogus/msToken urls are one-shot signed replays of it)
        if (rec.reqBody && isSendPath(url) &&
            !/[?&](msToken|a_bogus|X-Bogus|_signature)=/.test(url))
          stashSend(url, method, rec.reqHeaders, rec.reqBody);
      } catch (e) {}
      emit({ ...rec, ev: 'open' });

      const p = real.apply(this, arguments);
      p.then((res) => {
        emit({ ...rec, ev: 'headers', status: res.status });
        try {
          const clone = res.clone();
          if (clone.body && clone.body.getReader) {
            const reader = clone.body.getReader();
            let seq = 0;
            (function pump() {
              reader.read().then(({ done, value }) => {
                if (value && value.length)
                  emit({ ...rec, ev: 'chunk', seq: seq++, data: b64(value) });
                if (done) emit({ ...rec, ev: 'done' });
                else pump();
              }).catch(() => emit({ ...rec, ev: 'err' }));
            })();
          } else {
            clone.arrayBuffer().then((ab) => emit({ ...rec, ev: 'body', data: b64(ab) }))
              .catch(() => emit({ ...rec, ev: 'err' }));
          }
        } catch (e) { emit({ ...rec, ev: 'err' }); }
      }).catch((e) => emit({ ...rec, ev: 'err', err: String(e) }));
      return p;
    };
    f.__ib_wrapped = true;
    return f;
  }
  guard(window, 'fetch', wrapFetch);

  // ---------- XMLHttpRequest ----------
  function guardProto(proto, prop, wrap) {
    try {
      let wrapped = wrap(proto[prop]);
      Object.defineProperty(proto, prop, {
        configurable: true,
        get() { return wrapped; },
        set(v) { wrapped = wrap(v); },
      });
      return true;
    } catch (e) { return false; }
  }
  function wrapOpen(real) {
    if (typeof real !== 'function' || real.__ib_wrapped) return real;
    const f = function (m, u) {
      this.__ib_url = u; this.__ib_method = m;
      try { noteUrl(String(u)); } catch (e) {}
      return real.apply(this, arguments);
    };
    f.__ib_wrapped = true;
    return f;
  }
  function wrapSend(real) {
    if (typeof real !== 'function' || real.__ib_wrapped) return real;
    const f = function (body) {
      const url = this.__ib_url || '';
      if (watch(url)) {
        const rec = { k: 'xhr', url, method: this.__ib_method || 'POST', t: Date.now() };
        try {
          if (typeof body === 'string') {
            rec.reqBody = body.slice(0, 100000);
            if (isSendPath(url) &&
                !/[?&](msToken|a_bogus|X-Bogus|_signature)=/.test(url))
              stashSend(url, rec.method, null, rec.reqBody);
          }
        } catch (e) {}
        emit({ ...rec, ev: 'open' });
        let lastLen = 0;
        const poll = () => {
          try {
            const txt = this.responseText;
            if (txt && txt.length > lastLen) {
              const delta = txt.slice(lastLen);
              lastLen = txt.length;
              emit({ ...rec, ev: 'chunk', seq: (rec._s = (rec._s || 0) + 1), text: delta.slice(0, 100000) });
            }
          } catch (e) {}
        };
        this.addEventListener('progress', poll);
        this.addEventListener('load', () => { poll(); emit({ ...rec, ev: 'done', status: this.status }); });
        this.addEventListener('error', () => emit({ ...rec, ev: 'err' }));
      }
      return real.apply(this, arguments);
    };
    f.__ib_wrapped = true;
    return f;
  }
  guardProto(XMLHttpRequest.prototype, 'open', wrapOpen);
  guardProto(XMLHttpRequest.prototype, 'send', wrapSend);

  // ---------- WebSocket (diagnostic: Doubao IM uses a wss protobuf channel)
  function wrapWS(Real) {
    if (typeof Real !== 'function' || Real.__ib_wrapped) return Real;
    const W = function (url, protos) {
      const ws = protos ? new Real(url, protos) : new Real(url);
      emit({ k: 'ws', ev: 'open', url: String(url), t: Date.now() });
      return ws;
    };
    W.prototype = Real.prototype;
    W.CONNECTING = Real.CONNECTING; W.OPEN = Real.OPEN;
    W.CLOSING = Real.CLOSING; W.CLOSED = Real.CLOSED;
    W.__ib_wrapped = true;
    return W;
  }
  guard(window, 'WebSocket', wrapWS);

  // ---------- re-arm: the app may clobber our accessors via its own
  // Object.defineProperty — check every 2s and re-wrap anything lost.
  setInterval(() => {
    try {
      if (!(window.fetch && window.fetch.__ib_wrapped))
        guard(window, 'fetch', wrapFetch);
      if (!(XMLHttpRequest.prototype.send &&
            XMLHttpRequest.prototype.send.__ib_wrapped)) {
        guardProto(XMLHttpRequest.prototype, 'open', wrapOpen);
        guardProto(XMLHttpRequest.prototype, 'send', wrapSend);
      }
      if (!(window.WebSocket && window.WebSocket.__ib_wrapped))
        guard(window, 'WebSocket', wrapWS);
    } catch (e) {}
  }, 2000);

  emit({ k: 'meta', ev: 'hook_installed', url: location.href });
  return 'ok';
})();
