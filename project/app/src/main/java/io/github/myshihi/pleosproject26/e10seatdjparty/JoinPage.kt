package io.github.myshihi.pleosproject26.e10seatdjparty

/** Mobile / seat-screen page served at /s/{token}. All dynamic text goes through textContent, never innerHTML. */
object JoinPage {
    val HOME = """<!doctype html><html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>모두의 DJ</title><style>body{margin:0;background:#14121d;color:#f7f2ec;font-family:system-ui,sans-serif;padding:32px 20px}p{color:#aea5bb}</style>
</head><body><h1>모두의 DJ</h1><p>차량 화면의 좌석별 QR 코드를 스캔해 주세요.</p></body></html>"""

    val PAGE = """<!doctype html>
<html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>모두의 DJ · 내 선곡</title>
<style>
:root{--bg:#14121d;--panel:#221e2f;--line:#494158;--ink:#f7f2ec;--muted:#aea5bb;--lime:#d1fa88;--lav:#b6a1f5}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--ink);font-family:system-ui,-apple-system,"Apple SD Gothic Neo","Noto Sans KR",sans-serif;line-height:1.45}
main{max-width:560px;margin:0 auto;padding:20px 16px 40px}
.brand{font-size:13px;letter-spacing:.12em;color:var(--muted)}h1{font-size:26px;margin:6px 0 2px}
.sub{color:var(--muted);font-size:15px;margin:0 0 16px}
.card{background:var(--panel);border-radius:16px;padding:16px;margin:12px 0}
.status{font-size:16px}.status.ok{color:var(--lime)}.status.warn{color:#f4ba82}
form{display:flex;gap:8px}input{flex:1;min-width:0;font-size:17px;padding:12px 14px;border-radius:12px;border:1px solid var(--line);background:#1b1826;color:var(--ink)}
button{font-size:16px;padding:12px 16px;border-radius:12px;border:0;background:var(--lime);color:#14121d;font-weight:600}
button[disabled]{opacity:.4}
ul{list-style:none;padding:0;margin:8px 0 0}li{display:flex;align-items:center;gap:12px;padding:12px 0;border-top:1px solid var(--line)}
li:first-child{border-top:0}.t{flex:1;min-width:0}.t b{display:block;font-size:16px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.t span{display:block;color:var(--muted);font-size:13px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
li button{background:transparent;color:var(--lime);border:1px solid var(--lime);padding:9px 12px}
.hint{color:var(--muted);font-size:13px}
</style></head><body><main>
<div class="brand">모두의 DJ</div>
<h1 id="seat">연결 중…</h1><p class="sub" id="round"></p>
<div class="card"><div class="status" id="status">차량 앱에 연결하고 있어요.</div></div>
<div class="card" id="searchCard" hidden>
<form id="form"><input id="q" type="search" maxlength="80" placeholder="곡·가수 검색 (예: 밤양갱 - 비비)" autocomplete="off"><button id="go">검색</button></form>
<p class="hint" id="source"></p><p class="hint">목록에 없으면 「제목 - 가수」로 검색하고 맨 아래 ‘이름으로 요청’을 고르세요.</p><ul id="list"></ul></div>
<p class="hint">이 페이지는 차 안 네트워크에서만 열려요. 계정 정보나 개인정보를 받지 않아요.<br>한 번 확정한 곡은 이번 라운드에서 바꿀 수 없어요.</p>
</main><script>
(function(){
var token=location.pathname.split('/').pop();var busy=false;var canPick=false;
function el(id){return document.getElementById(id)}
function api(path,opt){return fetch(path+(path.indexOf('?')<0?'?':'&')+'t='+encodeURIComponent(token),opt).then(function(r){return r.json()})}
function setStatus(text,cls){var s=el('status');s.textContent=text;s.className='status'+(cls?' '+cls:'')}
function render(st){
 el('seat').textContent=st.seat+'의 선곡';
 el('round').textContent='라운드 '+st.round+' · 참여 좌석 '+st.pickedCount+'/'+st.participants+'곳 선곡 완료';
 el('source').textContent='음악: '+st.source;
 canPick=!!st.canPick;
 el('searchCard').hidden=!canPick;
 if(st.picked){setStatus('내 선곡: '+st.pickTitle+' · 다른 좌석을 기다리는 중','ok')}
 else if(canPick){if(!busy)setStatus('검색해서 이번 라운드에 들을 한 곡을 골라 주세요.')}
 else setStatus(st.message,'warn');
 Array.prototype.forEach.call(document.querySelectorAll('li button'),function(b){b.disabled=!canPick||busy});
}
function poll(){return api('/api/state').then(render).catch(function(){setStatus('차량 앱과 연결이 끊겼어요. 차량 화면에서 모두의 DJ를 열어 두었는지 확인해 주세요.','warn')})}
function search(q){
 var list=el('list');list.textContent='';setStatus('검색 중…');
 api('/api/search?q='+encodeURIComponent(q)).then(function(r){
  if(!r.ok){setStatus(r.message,'warn');return}
  if(!r.items.length){setStatus(q?'검색 결과가 없어요. 다른 검색어로 찾아 주세요.':'곡·가수 이름으로 검색해 주세요.');return}
  setStatus(r.items.length+'곡을 찾았어요. 한 곡을 골라 주세요.');
  r.items.forEach(function(it){
   var li=document.createElement('li');var t=document.createElement('div');t.className='t';
   var b=document.createElement('b');b.textContent=it.title;var s=document.createElement('span');s.textContent=it.subtitle;
   t.appendChild(b);t.appendChild(s);var btn=document.createElement('button');btn.textContent='이 곡 선택';btn.disabled=!canPick;
   btn.onclick=function(){pick(it)};li.appendChild(t);li.appendChild(btn);list.appendChild(li);
  });
 }).catch(function(){setStatus('검색하지 못했어요. 다시 시도해 주세요.','warn')});
}
function pick(it){
 if(busy||!confirm('「'+it.title+'」(으)로 확정할까요?\n이번 라운드에는 바꿀 수 없어요.'))return;
 busy=true;
 api('/api/pick',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:'key='+encodeURIComponent(it.key)})
 .then(function(r){setStatus(r.message,r.ok?'ok':'warn');if(r.ok)el('list').textContent=''})
 .catch(function(){setStatus('확정하지 못했어요. 다시 시도해 주세요.','warn')})
 .then(function(){busy=false;poll()});
}
el('form').onsubmit=function(e){e.preventDefault();search(el('q').value.trim())};
poll().then(function(){if(canPick)search('')});setInterval(poll,4000);
})();
</script></body></html>"""
}
