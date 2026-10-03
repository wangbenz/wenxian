const $ = id => document.getElementById(id);
function requestUuid(){
  if(crypto.randomUUID)return crypto.randomUUID();
  const bytes=new Uint8Array(16);crypto.getRandomValues(bytes);bytes[6]=(bytes[6]&15)|64;bytes[8]=(bytes[8]&63)|128;
  const hex=[...bytes].map(b=>b.toString(16).padStart(2,'0')).join('');
  return `${hex.slice(0,8)}-${hex.slice(8,12)}-${hex.slice(12,16)}-${hex.slice(16,20)}-${hex.slice(20)}`;
}
const storageKey = 'loreforge-dm-saves';
let sessions = [], current = null, save = null, scenario = null, status = null, pending = null, timer = null;
try { sessions = JSON.parse(localStorage.getItem(storageKey) || '[]'); } catch { sessions = []; }
const names = { shears:'剪草小剪', note:'纸坊取料便条', protection:'护身符', grass:'七星草纸料' };
const itemKeys = { protection:'xianxia_talisman-protection', grass:'xianxia_plant-seven-star-grass' };
const goals = { ONGOING:'尚在进行', DELIVERED:'纸料已交付', ABANDONED:'已放下这次委托' };
const facts = { OBSERVED:'亲历与观察', STATEMENT:'人物说法', PLAN:'计划' };
function storyTitle(){return save?.adventure?.title||'雨前取草';}
function itemInfo(key){return save?.adventure?.items?.[key]||{name:names[key]||key,unit:key==='grass'?'束':key==='protection'||key==='note'?'张':'件',reference:itemKeys[key]};}
function node(tag, text, className) { const el=document.createElement(tag); if(text!==undefined)el.textContent=text; if(className)el.className=className; return el; }
function paragraphs(parent, text) { parent.replaceChildren(...text.split(/\n+/).filter(Boolean).map(p=>node('p',p))); }
async function api(path, options={}) {
  const headers={...options.headers}; if(current)headers['X-Demo-Token']=current.token;
  if(options.body)headers['Content-Type']='application/json';
  const controller=new AbortController();
  const timeout=setTimeout(()=>controller.abort(),path.endsWith('/turns')?300000:15000);
  try {
    const response=await fetch(path,{...options,headers,signal:controller.signal});
    const data=await response.json(); if(!response.ok){const error=new Error(data.message||'连接暂时不可用');error.status=response.status;throw error;} return data;
  } finally {clearTimeout(timeout);}
}
function remember() { localStorage.setItem(storageKey,JSON.stringify(sessions)); }
function sessionOptions() {
  $('saves').replaceChildren(node('option','选择一局'));
  $('saves').firstChild.value='';
  for(const s of [...sessions].reverse()){const option=node('option',s.label);option.value=s.id;$('saves').append(option);}
  $('resume-label').hidden=!sessions.length;$('no-saves').hidden=!!sessions.length;
}
async function refreshStatus(){
  try{status=await api('/api/status');$('dm-status').textContent=status.modelReady?(status.remainingRequests>=3?'DM 已就绪':'本次测试额度不足一轮'):'DM 尚未启用';busy(!!pending && pending.processing);}
  catch{$('dm-status').textContent='暂时无法连接';}
}
function busy(value){
  const locked=value||!!pending?.uncertain;
  $('submit').disabled=locked||!status?.modelReady||status.remainingRequests<3;
  $('action').disabled=locked;document.querySelectorAll('[name=mode]').forEach(x=>x.disabled=locked);
  $('new-game').disabled=value;$('save-menu').disabled=value;$('start').disabled=value;$('scenario').disabled=value;$('suggestions').querySelectorAll('button').forEach(x=>x.disabled=locked);
}
function updateSave(value){
  if(!current||value.id!==current.id)return;
  if(save&&value.id===save.id&&value.revision<save.revision)return;
  save=value;localStorage.setItem('loreforge-dm-active',current.id);current.label=`${storyTitle()} · ${save.revision} 次回应 · ${new Date(current.created).toLocaleString('zh-CN',{month:'numeric',day:'numeric',hour:'2-digit',minute:'2-digit'})}`;
  const index=sessions.findIndex(s=>s.id===current.id);if(index>=0)sessions[index]=current;remember();sessionOptions();render();
}
function render(){
  $('welcome').hidden=true;$('play').hidden=false;$('global-error').hidden=true;$('goal').textContent=save.objective;$('story-title').textContent=storyTitle();document.title=storyTitle()+' · DM试玩';
  const openingParts=save.opening.split(/\n+/).filter(Boolean);
  const expanded=$('opening').querySelector('details')?.open ?? matchMedia('(min-width:721px)').matches;
  $('opening').replaceChildren(node('p',openingParts[0]||''));
  if(openingParts.length>1){const detail=node('details');detail.open=expanded;detail.append(node('summary','查看现场与已学本领'));openingParts.slice(1).forEach(p=>detail.append(node('p',p)));$('opening').append(detail);}
  $('turns').replaceChildren();
  for(const t of save.turns){
    const article=node('article',undefined,'turn');
    article.append(node('div',`第 ${t.number} 次回应 · ${t.mode==='ASK'?'你问 DM':'你采取行动'}`,'turn-label'),node('div',t.action,'player-action'),node('div',t.narrative,'narrative'),node('p',t.ruling,'ruling'));
    if(t.receipt?.length){const receipt=node('ul',undefined,'receipt');for(const line of t.receipt)receipt.append(node('li',line));article.append(receipt);}
    const fresh=t.readings.filter(r=>r.access!=='REUSED').length,reused=t.readings.length-fresh;const details=node('details',undefined,'reading-details');details.append(node('summary',fresh?`本轮查阅 ${fresh} 条资料${reused?` · 沿用 ${reused} 条`:''}`:reused?`沿用 ${reused} 条已查资料`:'本轮沿用现场与已知经历'));
    const links=node('div',undefined,'reading-links');for(const r of t.readings){const b=node('button',r.name+(r.access==='REUSED'?' · 沿用':r.used?' · 采用':' · 查阅'));b.type='button';b.addEventListener('click',()=>reference(r.key));links.append(b);}details.append(links,node('p','资料定义来自开物，角色是否学会、持有或实际发生，由本局经历分别记录。','reading-hint'));article.append(details);$('turns').append(article);
  }
  $('state').replaceChildren();for(const [label,value] of [['所在',''+save.location],['身体与法力',save.condition],['时间',`午后起，已过 ${save.minutes} 分钟`],['此行进展',save.adventure?.ending||goals[save.goal]||save.goal]])$('state').append(node('dt',label),node('dd',value));
  for(const note of save.adventure?.notes||[]){if(note.name==='此行结果')continue;$('state').append(node('dt',note.name),node('dd',note.value));}
  $('inventory').replaceChildren();for(const [key,amount] of Object.entries(save.inventory.PLAYER)){if(!amount)continue;const row=node('li'),info=itemInfo(key);if(info.reference){const b=node('button',info.name);b.type='button';b.addEventListener('click',()=>reference(info.reference));row.append(b);}else row.append(node('div',info.name));row.append(node('span',`${amount} ${info.unit}`));$('inventory').append(row);}
  $('abilities').replaceChildren();for(const key of save.abilities){const li=node('li'),b=node('button',save.lore[key]?.name||'已学本领');b.type='button';b.addEventListener('click',()=>reference(key));li.append(b);$('abilities').append(li);}
  $('facts').replaceChildren();for(const f of save.facts){const li=node('li',f.text);li.prepend(node('small',`第 ${f.turn} 次 · ${facts[f.kind]||'记录'}`));$('facts').append(li);}
  if(!save.facts.length)$('facts').append(node('li','正式行动发生后会记在这里。'));
  $('suggestions').replaceChildren();const suggestions=save.turns.at(-1)?.suggestions||save.adventure?.suggestions||['向守圃人说明来意，询问怎样取草','先观察药畦、栅门和引水木槽','询问 DM：控物术能直接拔起活草吗？'];
  for(const suggestion of suggestions){const button=node('button',suggestion);button.type='button';button.addEventListener('click',()=>{let value=suggestion;const ask=value.startsWith('询问 DM：');if(ask)value=value.slice(6);document.querySelector(`[name=mode][value=${ask?'ASK':'ACT'}]`).checked=true;$('action').value=value;persistDraft();$('action').focus();});$('suggestions').append(button);}
  busy(!!pending?.processing);
}
function reference(key){
  const entry=save?.lore[key];if(!entry)return;
  $('reference-title').textContent=entry.name;$('reference-meta').textContent='本局固定的开物资料 · 查看定义不消耗试玩行动';
  const parent=$('reference-body');parent.replaceChildren();
  for(const paragraph of entry.text.split(/\n\s*\n/)){
    if(paragraph.startsWith('### ')){parent.append(node('h3',paragraph.slice(4)));continue;}
    const match=paragraph.match(/^\*\*([^*]+)\*\*\s*([\s\S]*)$/);if(match){parent.append(node('h3',match[1]),node('p',match[2]));continue;}
    parent.append(node('p',paragraph.replace(/\[([^\]]+)\]\([^)]*\)/g,'$1').replaceAll('**','')));
  }
  $('reference-link').href=`${location.protocol}//${location.hostname}:5178${entry.pageUrl}`;
  $('reference-dialog').showModal();
}
function draftKey(){return 'loreforge-dm-draft-'+current?.id;}
function persistDraft(){if(current)localStorage.setItem(draftKey(),JSON.stringify({action:$('action').value,mode:document.querySelector('[name=mode]:checked').value}));}
function restoreDraft(){try{const d=JSON.parse(localStorage.getItem(draftKey())||'{}');$('action').value=d.action||'';document.querySelector(`[name=mode][value=${d.mode==='ASK'?'ASK':'ACT'}]`).checked=true;}catch{}}
function persistPending(){if(!current)return;if(pending)localStorage.setItem('loreforge-dm-pending-'+current.id,JSON.stringify(pending));else localStorage.removeItem('loreforge-dm-pending-'+current.id);}
function showPending(){
  $('error').hidden=true;$('pending').hidden=false;$('pending').replaceChildren(node('p',pending.request.action,'player-action'),node('small','DM 正在处理本次输入。刷新页面也能取回同一项请求。'));
  pending.processing=true;persistPending();busy(true);
}
function failed(message,uncertain=false){if(pending){pending.processing=false;pending.uncertain=uncertain;persistPending();}$('dismiss-error').disabled=uncertain;$('retry').disabled=!pending;$('pending').hidden=true;$('error').hidden=false;$('error-text').textContent=message;busy(false);refreshStatus();}
async function resolve(result){
  if(result.status==='COMMITTED'){
    pending=null;persistPending();clearTimeout(timer);$('pending').hidden=true;$('error').hidden=true;updateSave(result.save);$('action').value='';persistDraft();await refreshStatus();$('action-form').scrollIntoView({block:'center',behavior:'auto'});return;
  }
  if(result.status==='FAILED'||result.status==='MISSING'){failed(result.message||'尚未确认结果，可重试同一项输入');return;}
  schedulePoll();
}
function schedulePoll(){clearTimeout(timer);timer=setTimeout(recover,2000);}
async function recover(){
  if(!pending||!current)return;
  const sessionId=current.id,requestId=pending.request.requestId;
  try{const result=await api(`/api/sessions/${sessionId}/requests/${requestId}`);if(current?.id===sessionId&&pending?.request.requestId===requestId)await resolve(result);}
  catch{if(current?.id!==sessionId||pending?.request.requestId!==requestId)return;failed('暂时无法确认本次结果，输入已保留。请先重试确认这项请求。',true);}
}
async function send(retry=false){
  const sessionId=current.id,requestId=pending.request.requestId;
  showPending();schedulePoll();
  try{const result=await api(`/api/sessions/${current.id}/turns`,{method:'POST',body:JSON.stringify({request:pending.request,retry})});if(current?.id===sessionId&&pending?.request.requestId===requestId)await resolve(result);}
  catch(e){if(current?.id!==sessionId||pending?.request.requestId!==requestId)return;clearTimeout(timer);if(e.status===409){pending=null;persistPending();try{updateSave(await api(`/api/sessions/${current.id}`));}catch{}failed(e.message);}else await recover();}
}
async function start(){
  clearTimeout(timer);
  try{busy(true);const created=await api('/api/sessions',{method:'POST',body:JSON.stringify({scenario:$('scenario').value})});current={id:created.id,token:created.token,created:Date.now(),label:'新开场'};sessions.push(current);pending=null;save=null;updateSave(created.save);$('action').value='';document.querySelector('[name=mode][value=ACT]').checked=true;await refreshStatus();}
  catch(e){$('global-error').hidden=false;$('global-error').textContent=e.message;busy(false);}
}
async function resume(id){
  const target=sessions.find(s=>s.id===id);if(!target)return;
  clearTimeout(timer);pending=null;current=target;
  try{save=null;const restored=await api(`/api/sessions/${id}`);if(current?.id!==id)return;updateSave(restored);restoreDraft();pending=JSON.parse(localStorage.getItem('loreforge-dm-pending-'+id)||'null');if(pending){showPending();await recover();}}
  catch(e){$('global-error').hidden=false;$('global-error').textContent=e.message;}
}
$('action-form').addEventListener('submit',async event=>{event.preventDefault();if(pending?.processing||pending?.uncertain||!save||!$('action').value.trim())return;persistDraft();pending={request:{requestId:requestUuid(),expectedRevision:save.revision,mode:document.querySelector('[name=mode]:checked').value,action:$('action').value.trim()},processing:true};await send();});
$('retry').addEventListener('click',()=>{if(pending)send(true);});
$('dismiss-error').addEventListener('click',()=>{pending=null;persistPending();clearTimeout(timer);$('error').hidden=true;busy(false);$('action').focus();});
$('start').addEventListener('click',start);$('new-game').addEventListener('click',start);
$('saves').addEventListener('change',()=>{$('saves-dialog').close();resume($('saves').value);});$('save-menu').addEventListener('click',()=>{$('saves-dialog').showModal();});$('close-saves').addEventListener('click',()=>$('saves-dialog').close());$('close-reference').addEventListener('click',()=>$('reference-dialog').close());
$('action').addEventListener('input',persistDraft);document.querySelectorAll('[name=mode]').forEach(el=>el.addEventListener('change',persistDraft));
try{scenario=await api('/api/scenario');$('goal').textContent=scenario.goal;$('story-title').textContent=scenario.title;
  if(scenario.availableScenarios){$('scenario').replaceChildren(...scenario.availableScenarios.map(s=>{const option=node('option',s.label);option.value=s.id;return option;}));$('scenario').value=scenario.defaultScenario;}
  sessionOptions();await refreshStatus();if(sessions.length){const active=localStorage.getItem('loreforge-dm-active');await resume(sessions.some(s=>s.id===active)?active:sessions.at(-1).id);}
}catch(e){$('global-error').hidden=false;$('global-error').textContent=e.message;}
