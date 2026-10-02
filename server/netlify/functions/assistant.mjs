const SYSTEM = `
너는 119파이어 업무비서다. 한국어로 짧고 실무적으로 답한다.
현재 앱이 전달한 데이터만 사실로 취급한다.
없는 고객, 현장, 원인, 공사, 시험, 결과, 금액을 만들지 않는다.
문의/견적/회신/현장/할 일 정리를 우선한다.
블로그 관련 요청에서는 실제 기록만 사용하고 과장하지 않는다.
앱이 실제로 하지 않은 전화·문자·발송·예약 행동을 했다고 말하지 않는다.
`;

function extract(data){
  if(typeof data.output_text==="string" && data.output_text) return data.output_text;
  const parts=[];
  for(const item of (data.output||[])) for(const c of (item.content||[]))
    if(c.type==="output_text" && c.text) parts.push(c.text);
  return parts.join("\n").trim();
}

export default async (request) => {
  const H={"content-type":"application/json; charset=utf-8","cache-control":"no-store"};
  if(request.method!=="POST") return new Response(JSON.stringify({error:"POST only"}),{status:405,headers:H});
  // Accept the early V3 setup spelling too. Netlify variable names are
  // case-sensitive, so this avoids making the owner paste a secret again.
  const key=Netlify.env.get("OPENAI_API_KEY")||Netlify.env.get("OPENAi_api_key");
  const appCode=Netlify.env.get("APP_ACCESS_CODE");
  const model=Netlify.env.get("OPENAI_MODEL")||"gpt-5";
  if(!key||!appCode) return new Response(JSON.stringify({error:"서버 환경변수 설정이 필요합니다."}),{status:500,headers:H});
  let body={}; try{body=await request.json()}catch{}
  if(body.code!==appCode) return new Response(JSON.stringify({error:"앱 접속 코드가 맞지 않습니다."}),{status:401,headers:H});
  const context=JSON.stringify(body.context||{}).slice(0,40000);
  const message=String(body.message||"").slice(0,6000);
  try{
    const r=await fetch("https://api.openai.com/v1/responses",{
      method:"POST",
      headers:{"authorization":`Bearer ${key}`,"content-type":"application/json"},
      body:JSON.stringify({
        model,
        instructions:SYSTEM,
        input:[{role:"user",content:[{type:"input_text",text:`[앱 데이터]\n${context}\n\n[요청]\n${message}`}]}],
        max_output_tokens:1600,
        store:false
      })
    });
    const d=await r.json();
    if(!r.ok) return new Response(JSON.stringify({error:d?.error?.message||`OpenAI 오류 ${r.status}`}),{status:r.status,headers:H});
    return new Response(JSON.stringify({reply:extract(d),model}),{status:200,headers:H});
  }catch(e){
    return new Response(JSON.stringify({error:e.message}),{status:500,headers:H});
  }
};
