const SUMMARY_SYSTEM = `
너는 119파이어 통화 정리 비서다.
통화 전사문에 실제로 나온 내용만 요약한다.
추정해서 지역, 시설, 원인, 가격, 일정, 고객 의도를 만들지 않는다.
가능하면 다음 항목으로 간결하게 정리한다:
- 고객/현장
- 지역
- 문의 내용
- 요청 사항
- 일정/약속
- 견적/금액
- 다시 확인할 점
- 다음 행동
내용이 없으면 '확인되지 않음'이라고 쓴다.
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

  const key=Netlify.env.get("OPENAI_API_KEY");
  const appCode=Netlify.env.get("APP_ACCESS_CODE");
  const code=request.headers.get("x-app-code")||"";
  if(!key||!appCode) return new Response(JSON.stringify({error:"서버 환경변수 설정이 필요합니다."}),{status:500,headers:H});
  if(code!==appCode) return new Response(JSON.stringify({error:"앱 접속 코드가 맞지 않습니다."}),{status:401,headers:H});

  try{
    const form=await request.formData();
    const audio=form.get("audio");
    if(!audio || typeof audio.arrayBuffer!=="function")
      return new Response(JSON.stringify({error:"오디오 파일이 없습니다."}),{status:400,headers:H});
    if(audio.size>5*1024*1024)
      return new Response(JSON.stringify({error:"샘플 버전은 5MB 이하 파일만 지원합니다."}),{status:413,headers:H});

    const fd=new FormData();
    fd.append("file",audio,audio.name||"call.m4a");
    fd.append("model",Netlify.env.get("TRANSCRIBE_MODEL")||"gpt-4o-mini-transcribe");

    const tr=await fetch("https://api.openai.com/v1/audio/transcriptions",{
      method:"POST",
      headers:{"authorization":`Bearer ${key}`},
      body:fd
    });
    const td=await tr.json();
    if(!tr.ok) return new Response(JSON.stringify({error:td?.error?.message||`전사 오류 ${tr.status}`}),{status:tr.status,headers:H});
    const transcript=String(td.text||"").trim();

    const model=Netlify.env.get("OPENAI_MODEL")||"gpt-5.6-luna";
    const sr=await fetch("https://api.openai.com/v1/responses",{
      method:"POST",
      headers:{"authorization":`Bearer ${key}`,"content-type":"application/json"},
      body:JSON.stringify({
        model,
        instructions:SUMMARY_SYSTEM,
        input:[{role:"user",content:[{type:"input_text",text:`[통화 전사]\n${transcript}`}]}],
        max_output_tokens:1200
      })
    });
    const sd=await sr.json();
    if(!sr.ok) return new Response(JSON.stringify({error:sd?.error?.message||`요약 오류 ${sr.status}`}),{status:sr.status,headers:H});

    return new Response(JSON.stringify({transcript,summary:extract(sd),model}),{status:200,headers:H});
  }catch(e){
    return new Response(JSON.stringify({error:e.message}),{status:500,headers:H});
  }
};