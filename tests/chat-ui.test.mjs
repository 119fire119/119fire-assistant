import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

const script = fs.readFileSync(new URL('../app/src/main/assets/app.js', import.meta.url), 'utf8');
const ids = ['toast','todayLabel','metrics','briefing','priorityTasks','recentWork','inquiryList',
  'siteList','finishSiteSelect','photoList','estimateList','recordingInfo','voiceReply',
  'monitorEnabled','callHistoryEnabled','estimateRows','supplyTotal','vatTotal','grandTotal','chat','serverBase',
  'accessCode','stageGrid','attentionList','attentionCard','priorityCard','favoriteItems','estimateNotes','photoSyncEnabled',
  'assistantState','automationCard','automationRecent','backgroundAssistantEnabled','driveBackupEnabled'];
const elements = Object.fromEntries(ids.map(id => [id, {innerHTML:'', textContent:'', value:'',
  className:'', scrollTop:0, scrollHeight:200, checked:false, classList:{toggle:()=>{}}}]));
const data = {inquiries:[],sites:[],tasks:[],estimates:[],photos:[],calls:[],status:{}};
const context = {
  ...elements,
  document:{getElementById:id => elements[id], querySelectorAll:() => []},
  localStorage:{getItem:() => null, setItem:() => {}},
  Native:{getWorkspace:() => JSON.stringify(data), getDashboard:() => JSON.stringify({}),
    saveServerConnection:() => {}},
  setTimeout:() => {},
  Date, Number, String, JSON,
};
context.window = context;
vm.runInNewContext(script, context, {filename:'app.js'});
assert.match(elements.chat.innerHTML, /119파이어 AI 비서입니다/);
context.onAiResponse(JSON.stringify({ok:true, reply:'AI 연결 테스트 성공'}));
assert.match(elements.chat.innerHTML, /AI 연결 테스트 성공/);
context.onAiResponse(JSON.stringify({ok:false, error:'앱 접속 코드가 맞지 않습니다.'}));
assert.match(elements.chat.innerHTML, /앱 접속 코드가 맞지 않습니다/);
console.log('채팅 응답·오류 표시 테스트 통과');
