import { useState, useRef, useEffect } from "react";

const C = {
  bg:"#07090f", surface:"#0b0d18", hi:"#0f1220", hover:"#131628",
  border:"#181d30", borderHi:"#222840",
  cyan:"#22d3ee", cyanDim:"#22d3ee14", cyanMid:"#22d3ee38",
  green:"#34d399", greenDim:"#34d39914", greenMid:"#34d39938",
  red:"#f87171",  redDim:"#f8717114",  redMid:"#f8717138",
  amber:"#fbbf24",amberDim:"#fbbf2414",amberMid:"#fbbf2438",
  purple:"#a78bfa",purpleDim:"#a78bfa14",purpleMid:"#a78bfa38",
  text:"#dde3f0", sub:"#4a5878", muted:"#252e42",
};

const G = `
@import url('https://fonts.googleapis.com/css2?family=IBM+Plex+Mono:wght@400;500;600&family=DM+Sans:wght@400;500;600;700&display=swap');
*{box-sizing:border-box;margin:0;padding:0}
body{background:${C.bg};color:${C.text};font-family:'DM Sans',sans-serif;font-size:13px;min-height:100vh}
.mono{font-family:'IBM Plex Mono',monospace}
::-webkit-scrollbar{width:3px}::-webkit-scrollbar-track{background:transparent}::-webkit-scrollbar-thumb{background:${C.borderHi};border-radius:2px}
button,input,textarea{font-family:'DM Sans',sans-serif;border:none;background:none;color:${C.text};outline:none}
button{cursor:pointer}
@keyframes fadeDown{from{opacity:0;transform:translateY(-8px)}to{opacity:1;transform:translateY(0)}}
@keyframes fadeIn{from{opacity:0;transform:translateY(4px)}to{opacity:1;transform:translateY(0)}}
@keyframes spin{to{transform:rotate(360deg)}}
@keyframes pulse2{0%,100%{opacity:1}50%{opacity:.25}}
.fadeDown{animation:fadeDown .2s cubic-bezier(.16,1,.3,1) both}
.fadeIn{animation:fadeIn .15s ease both}
`;

/* ── primitives ── */
const Spin = ({s=13,c=C.cyan}) => (
  <svg width={s} height={s} viewBox="0 0 24 24" style={{animation:"spin .7s linear infinite",flexShrink:0}}>
    <circle cx="12" cy="12" r="10" fill="none" stroke={c+"33"} strokeWidth="3"/>
    <path d="M12 2a10 10 0 0 1 10 10" fill="none" stroke={c} strokeWidth="3" strokeLinecap="round"/>
  </svg>
);
const PBar = ({v,c=C.cyan,h=3}) => (
  <div style={{background:C.border,borderRadius:h,height:h,overflow:"hidden",flex:1}}>
    <div style={{width:`${Math.min(v,100)}%`,height:"100%",background:c,borderRadius:h,transition:"width .5s ease"}}/>
  </div>
);
const SL = ({children,action}) => (
  <div style={{display:"flex",justifyContent:"space-between",alignItems:"center",marginBottom:7}}>
    <div style={{fontSize:9,fontWeight:700,color:C.muted,textTransform:"uppercase",letterSpacing:".12em"}}>{children}</div>
    {action}
  </div>
);
const short = a => `${a.slice(0,8)}…${a.slice(-6)}`;

/* ── icons (inline svg paths) ── */
const CheckIco  = ({s=12,c=C.green})   => <svg width={s} height={s} viewBox="0 0 24 24" fill="none" stroke={c} strokeWidth="2.5" strokeLinecap="round"><polyline points="20 6 9 17 4 12"/></svg>;
const XIcon     = ({s=12,c=C.red})     => <svg width={s} height={s} viewBox="0 0 24 24" fill="none" stroke={c} strokeWidth="2.5" strokeLinecap="round"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>;
const WarnIco   = ({s=10,c=C.amber})   => <svg width={s} height={s} viewBox="0 0 24 24" fill="none" stroke={c} strokeWidth="2" strokeLinecap="round"><path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/><line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/></svg>;
const PlusIcon  = ({s=11,c="currentColor"}) => <svg width={s} height={s} viewBox="0 0 24 24" fill="none" stroke={c} strokeWidth="2.5" strokeLinecap="round"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>;
const TrashIcon = ({s=11,c=C.sub})     => <svg width={s} height={s} viewBox="0 0 24 24" fill="none" stroke={c} strokeWidth="2" strokeLinecap="round"><polyline points="3 6 5 6 21 6"/><path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"/><path d="M10 11v6M14 11v6"/></svg>;
const InfoIco   = ({s=12,c=C.cyan})    => <svg width={s} height={s} viewBox="0 0 24 24" fill="none" stroke={c} strokeWidth="2" strokeLinecap="round"><circle cx="12" cy="12" r="10"/><line x1="12" y1="16" x2="12" y2="12"/><line x1="12" y1="8" x2="12.01" y2="8"/></svg>;

/* ── data ── */
const NETS = [
  {id:"ETH",  icon:"⟠", label:"Ethereum", color:"#627EEA"},
  {id:"ARB",  icon:"△", label:"Arbitrum", color:"#28A0F0"},
  {id:"BASE", icon:"◆", label:"Base",     color:"#0052FF"},
  {id:"OP",   icon:"○", label:"Optimism", color:"#FF0420"},
  {id:"POL",  icon:"⬡", label:"Polygon",  color:"#7B3FE4"},
  {id:"BSC",  icon:"◈", label:"BNB Chain",color:"#F0B90B"},
  {id:"AVAX", icon:"▲", label:"Avalanche",color:"#E84142"},
  {id:"MNT",  icon:"◉", label:"Mantle",   color:"#60a5fa"},
  {id:"LINEA",icon:"⬤", label:"Linea",    color:"#61dfff"},
];
const EXISTING_WALLETS = [
  "0xd8da6bf26964af9d7eed9e03e53415d37aa96045",
  "0x47ac0fb4f2d84898e4d9e7b4dab3c24507a6d503",
];

const WALLET_COLORS = [C.cyan, C.purple, C.green, C.amber, C.blue="#60a5fa", "#f472b6", "#34d399"];

/* ── validation ── */
const isEVM  = a => /^0x[0-9a-fA-F]{40}$/.test(a.trim());
const isDup  = a => EXISTING_WALLETS.includes(a.trim().toLowerCase());

const validateAddr = (addr) => {
  const t = addr.trim();
  if (!t) return { state: "empty" };
  if (!isEVM(t)) return { state: "err", msg: "Invalid EVM address" };
  if (isDup(t))  return { state: "warn", msg: "Already tracked — new networks will be added" };
  return { state: "ok", ens: t === "0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045".toLowerCase() ? "vitalik.eth" : null };
};

/* ═══════════════════════════════════════════════════════════
   WALLET ROW — single address entry with validation
═══════════════════════════════════════════════════════════ */
const WalletRow = ({ entry, index, onChange, onRemove, canRemove, color }) => {
  const { state, msg, ens } = validateAddr(entry.addr);
  const borderColor = state==="ok"?C.green+"66" : state==="err"?C.red+"66" : state==="warn"?C.amber+"66" : C.border;

  return (
    <div className="fadeIn" style={{display:"flex",flexDirection:"column",gap:5}}>
      <div style={{display:"flex",gap:6,alignItems:"center"}}>
        {/* Color dot */}
        <div style={{width:8,height:8,borderRadius:"50%",background:color,flexShrink:0,marginTop:1}}/>

        {/* Address input */}
        <div style={{position:"relative",flex:1}}>
          <input
            value={entry.addr}
            onChange={e=>onChange({...entry,addr:e.target.value})}
            placeholder={`Wallet ${index+1}  ·  0x…`}
            spellCheck={false}
            autoComplete="off"
            style={{
              width:"100%",background:C.hi,border:`1px solid ${borderColor}`,
              borderRadius:6,color:C.text,padding:"8px 32px 8px 10px",
              fontSize:12,fontFamily:"'IBM Plex Mono',monospace",
              transition:"border-color .12s",
            }}
          />
          {/* Status icon */}
          {state!=="empty" && (
            <div style={{position:"absolute",right:9,top:"50%",transform:"translateY(-50%)",pointerEvents:"none"}}>
              {state==="ok"   && <CheckIco s={13} c={C.green}/>}
              {state==="err"  && <XIcon    s={13} c={C.red}/>}
              {state==="warn" && <WarnIco  s={13} c={C.amber}/>}
            </div>
          )}
        </div>

        {/* Label input — compact */}
        <input
          value={entry.label}
          onChange={e=>onChange({...entry,label:e.target.value})}
          placeholder="Label"
          maxLength={20}
          style={{
            width:96,background:C.hi,border:`1px solid ${C.border}`,
            borderRadius:6,color:C.text,padding:"8px 9px",fontSize:11,
            transition:"border-color .12s",
          }}
        />

        {/* Remove */}
        {canRemove && (
          <button onClick={onRemove}
            style={{padding:5,color:C.sub,borderRadius:4,display:"flex",
              transition:"color .1s",flexShrink:0}}
            onMouseOver={e=>e.currentTarget.style.color=C.red}
            onMouseOut={e=>e.currentTarget.style.color=C.sub}>
            <TrashIcon s={13}/>
          </button>
        )}
      </div>

      {/* Inline feedback */}
      {msg && (
        <div style={{paddingLeft:14,fontSize:10,display:"flex",gap:4,alignItems:"center",
          color:state==="warn"?C.amber:C.red}}>
          {state==="warn"?<WarnIco s={9} c={C.amber}/>:<XIcon s={9} c={C.red}/>}
          {msg}
        </div>
      )}
      {state==="ok" && ens && (
        <div style={{paddingLeft:14}} className="mono">
          <span style={{fontSize:9,color:C.sub}}>ENS: </span>
          <span style={{fontSize:9,color:C.cyan}}>{ens}</span>
        </div>
      )}
    </div>
  );
};

/* ═══════════════════════════════════════════════════════════
   NETWORK SELECTOR — shared across all wallets
═══════════════════════════════════════════════════════════ */
const NetworkSelector = ({selected, onChange}) => {
  const all = selected.length===NETS.length;
  return (
    <div>
      <SL action={
        <button onClick={()=>onChange(all?[]:NETS.map(n=>n.id))}
          style={{fontSize:10,color:C.sub,fontWeight:600,padding:"1px 7px",
            border:`1px solid ${C.border}`,borderRadius:4,background:C.hi}}>
          {all?"Deselect all":"Select all"}
        </button>
      }>Networks · applied to all wallets</SL>
      <div style={{display:"grid",gridTemplateColumns:"repeat(3,1fr)",gap:5}}>
        {NETS.map(n=>{
          const on = selected.includes(n.id);
          return (
            <button key={n.id} onClick={()=>onChange(on?selected.filter(x=>x!==n.id):[...selected,n.id])}
              style={{
                display:"flex",alignItems:"center",gap:6,
                padding:"6px 8px",borderRadius:6,textAlign:"left",
                background:on?n.color+"18":C.hi,
                border:`1px solid ${on?n.color+"55":C.border}`,
                color:on?n.color:C.sub,
                fontSize:11,fontWeight:600,transition:"all .1s",
              }}>
              <div style={{width:12,height:12,borderRadius:3,flexShrink:0,
                border:`2px solid ${on?n.color:C.border}`,
                background:on?n.color:"transparent",
                display:"flex",alignItems:"center",justifyContent:"center",transition:"all .1s"}}>
                {on&&<svg width="7" height="7" viewBox="0 0 24 24" fill="none" stroke="#000" strokeWidth="3.5" strokeLinecap="round"><polyline points="20 6 9 17 4 12"/></svg>}
              </div>
              <span style={{fontSize:13,lineHeight:1}}>{n.icon}</span>
              <span style={{overflow:"hidden",textOverflow:"ellipsis",whiteSpace:"nowrap"}}>{n.label}</span>
            </button>
          );
        })}
      </div>
    </div>
  );
};

/* ═══════════════════════════════════════════════════════════
   REVIEW STEP — summary before submit
═══════════════════════════════════════════════════════════ */
const ReviewStep = ({wallets, nets, onBack, onConfirm, confirming}) => (
  <div className="fadeIn" style={{display:"flex",flexDirection:"column",gap:0}}>
    {/* Header */}
    <div style={{padding:"13px 16px 11px",borderBottom:`1px solid ${C.border}`}}>
      <div style={{fontWeight:700,fontSize:14,marginBottom:2}}>Confirm backfill</div>
      <div style={{fontSize:11,color:C.sub}}>
        {wallets.length} wallet{wallets.length>1?"s":""} · {nets.length} network{nets.length>1?"s":""}
        {" · "}est. {wallets.length * nets.length * 2}–{wallets.length * nets.length * 5} min total
      </div>
    </div>

    <div style={{padding:"12px 16px",display:"flex",flexDirection:"column",gap:10,maxHeight:300,overflowY:"auto"}}>
      {wallets.map((w,i)=>{
        const {state,ens} = validateAddr(w.addr);
        return (
          <div key={i} style={{display:"flex",alignItems:"flex-start",gap:8,
            padding:"8px 10px",background:C.hi,border:`1px solid ${C.border}`,borderRadius:7}}>
            <div style={{width:8,height:8,borderRadius:"50%",background:WALLET_COLORS[i%WALLET_COLORS.length],flexShrink:0,marginTop:4}}/>
            <div style={{flex:1,minWidth:0}}>
              <div style={{display:"flex",alignItems:"center",gap:6,marginBottom:2}}>
                <span style={{fontWeight:600,fontSize:12}}>{w.label||`Wallet ${i+1}`}</span>
                {state==="warn"&&<span style={{fontSize:9,color:C.amber,fontWeight:600}}>EXISTS · new nets only</span>}
              </div>
              <div className="mono" style={{fontSize:10,color:C.sub,wordBreak:"break-all"}}>{w.addr.trim()}</div>
              {ens&&<div style={{fontSize:9,color:C.cyan,marginTop:2}}>{ens}</div>}
            </div>
          </div>
        );
      })}

      {/* Networks summary */}
      <div style={{padding:"8px 10px",background:C.hi,border:`1px solid ${C.border}`,borderRadius:7}}>
        <div style={{fontSize:9,color:C.muted,textTransform:"uppercase",letterSpacing:".1em",marginBottom:6}}>Networks</div>
        <div style={{display:"flex",flexWrap:"wrap",gap:5}}>
          {nets.map(nid=>{
            const n=NETS.find(x=>x.id===nid);
            return (
              <span key={nid} style={{display:"inline-flex",alignItems:"center",gap:4,
                padding:"2px 8px",borderRadius:4,background:n.color+"18",
                border:`1px solid ${n.color}44`,fontSize:10,fontWeight:600,color:n.color}}>
                {n.icon} {n.label}
              </span>
            );
          })}
        </div>
      </div>
    </div>

    {/* Actions */}
    <div style={{padding:"10px 16px",borderTop:`1px solid ${C.border}`,display:"flex",gap:8,justifyContent:"flex-end"}}>
      <button onClick={onBack} disabled={confirming}
        style={{padding:"7px 14px",border:`1px solid ${C.border}`,borderRadius:5,color:C.sub,fontSize:12}}>
        ← Back
      </button>
      <button onClick={onConfirm} disabled={confirming}
        style={{display:"flex",alignItems:"center",gap:6,padding:"7px 18px",
          background:C.cyan,color:"#000",border:"none",borderRadius:5,fontWeight:700,fontSize:12,minWidth:130,justifyContent:"center"}}>
        {confirming?<><Spin s={13} c="#000"/>Starting…</>:<>Start backfill</>}
      </button>
    </div>
  </div>
);

/* ═══════════════════════════════════════════════════════════
   BACKFILL PROGRESS STEP
═══════════════════════════════════════════════════════════ */
const BackfillStep = ({wallets, nets, progress, onClose}) => {
  const allDone = wallets.every(w =>
    nets.every(nid => (progress[`${w.addr.trim().slice(0,10)}_${nid}`]||0) >= 100)
  );

  return (
    <div className="fadeIn">
      {/* Header */}
      <div style={{padding:"13px 16px 11px",borderBottom:`1px solid ${C.border}`,
        display:"flex",justifyContent:"space-between",alignItems:"flex-start"}}>
        <div>
          <div style={{fontWeight:700,fontSize:14,display:"flex",alignItems:"center",gap:8,marginBottom:2}}>
            {allDone
              ?<><CheckIco s={14} c={C.green}/>Backfill complete</>
              :<><Spin s={14}/>Backfill running…</>
            }
          </div>
          <div style={{fontSize:11,color:C.sub}}>
            {wallets.length} wallet{wallets.length>1?"s":""} · {nets.length} network{nets.length>1?"s":""}
          </div>
        </div>
        {allDone && (
          <button onClick={onClose}
            style={{padding:"5px 14px",background:C.cyan,color:"#000",
              border:"none",borderRadius:5,fontWeight:700,fontSize:11}}>
            Done
          </button>
        )}
      </div>

      {/* Per-wallet × per-network grid */}
      <div style={{maxHeight:340,overflowY:"auto",padding:"12px 16px",display:"flex",flexDirection:"column",gap:12}}>
        {wallets.map((w,wi)=>{
          const wColor = WALLET_COLORS[wi%WALLET_COLORS.length];
          const wLabel = w.label||`Wallet ${wi+1}`;
          const wKey   = w.addr.trim().slice(0,10);
          const wDone  = nets.every(nid=>(progress[`${wKey}_${nid}`]||0)>=100);
          const wPct   = Math.round(nets.reduce((s,nid)=>s+(progress[`${wKey}_${nid}`]||0),0)/nets.length);

          return (
            <div key={wi} style={{background:C.hi,border:`1px solid ${wDone?wColor+"33":C.border}`,borderRadius:8,overflow:"hidden"}}>
              {/* Wallet header */}
              <div style={{padding:"8px 11px",borderBottom:`1px solid ${C.border}`,
                display:"flex",alignItems:"center",gap:7}}>
                <div style={{width:7,height:7,borderRadius:"50%",background:wColor,flexShrink:0}}/>
                <span style={{fontWeight:600,fontSize:12,flex:1}}>{wLabel}</span>
                <span className="mono" style={{fontSize:9,color:C.sub}}>{short(w.addr.trim())}</span>
                {wDone
                  ? <CheckIco s={12} c={C.green}/>
                  : <><span className="mono" style={{fontSize:10,color:wColor,fontWeight:600}}>{wPct}%</span><Spin s={11} c={wColor}/></>
                }
              </div>

              {/* Per-network rows */}
              <div style={{padding:"8px 11px",display:"flex",flexDirection:"column",gap:6}}>
                {nets.map(nid=>{
                  const n   = NETS.find(x=>x.id===nid);
                  const pct = Math.round(progress[`${wKey}_${nid}`]||0);
                  const done= pct>=100;
                  return (
                    <div key={nid} style={{display:"flex",alignItems:"center",gap:8}}>
                      <div style={{width:5,height:5,borderRadius:"50%",background:done?C.green:n.color,flexShrink:0}}/>
                      <span style={{fontSize:10,color:done?C.sub:n.color,fontWeight:500,width:78,flexShrink:0}}>
                        {n.icon} {n.label}
                      </span>
                      <PBar v={pct} c={done?C.green:n.color}/>
                      <span className="mono" style={{fontSize:9,color:done?C.green:C.text,fontWeight:600,width:30,textAlign:"right",flexShrink:0}}>
                        {pct}%
                      </span>
                      {!done&&<span style={{fontSize:8,color:C.muted,animation:"pulse2 1.4s ease infinite",flexShrink:0,width:46}}>scanning…</span>}
                      {done &&<span style={{fontSize:8,color:C.green,flexShrink:0,width:46}}>✓ done</span>}
                    </div>
                  );
                })}
              </div>
            </div>
          );
        })}
      </div>

      {/* Footer note */}
      <div style={{padding:"0 16px 14px"}}>
        {!allDone
          ?<div style={{padding:"7px 10px",background:C.cyanDim,border:`1px solid ${C.cyanMid}`,
            borderRadius:5,fontSize:11,color:C.sub,lineHeight:1.5}}>
            Backfill continues in background — you can close this panel.
            Partial results appear as transactions are indexed.
          </div>
          :<div style={{padding:"8px 10px",background:C.greenDim,border:`1px solid ${C.greenMid}`,
            borderRadius:5,display:"flex",gap:7,alignItems:"flex-start"}}>
            <CheckIco s={11} c={C.green}/>
            <span style={{fontSize:11,color:C.green,lineHeight:1.5}}>
              All wallets indexed. AVCO, P&L and balances are ready.
            </span>
          </div>
        }
      </div>
    </div>
  );
};

/* ═══════════════════════════════════════════════════════════
   MAIN MODAL
═══════════════════════════════════════════════════════════ */
const newEntry = () => ({id:Date.now()+Math.random(), addr:"", label:""});

const AddWalletModal = ({onClose}) => {
  const [wallets, setWallets] = useState([newEntry()]);
  const [nets,    setNets]    = useState(["ETH","ARB","BASE","OP","POL"]);
  const [step,    setStep]    = useState("form");    // form | review | confirming | backfill
  const [progress,setProgress]= useState({});
  const inputRefs = useRef({});

  /* validate all entries */
  const validatedWallets = wallets.map(w=>({...w,...validateAddr(w.addr)}));
  const hasAnyAddr  = wallets.some(w=>w.addr.trim());
  const allValid    = validatedWallets.filter(w=>w.addr.trim()).every(w=>w.state==="ok"||w.state==="warn");
  const readyCount  = validatedWallets.filter(w=>w.state==="ok"||w.state==="warn").length;
  const canReview   = readyCount>0 && allValid && nets.length>0;

  /* paste handler — bulk paste of multiple lines */
  const handlePaste = (e, idx) => {
    const text = e.clipboardData.getData("text");
    const lines = text.split(/[\n,\s]+/).map(l=>l.trim()).filter(l=>l.length>0);
    if (lines.length<=1) return; // normal single paste
    e.preventDefault();
    const existing = wallets[idx];
    const newEntries = lines.map((addr,i)=>({...newEntry(), addr, label: i===0&&existing.label?existing.label:""}));
    const updated = [...wallets.slice(0,idx), ...newEntries, ...wallets.slice(idx+1)];
    setWallets(updated.slice(0,10)); // cap at 10
  };

  const addRow    = () => { if(wallets.length<10) setWallets(w=>[...w,newEntry()]); };
  const removeRow = i  => setWallets(w=>w.filter((_,j)=>j!==i));
  const updateRow = (i,e) => setWallets(w=>{ const n=[...w]; n[i]=e; return n; });

  /* submit → start fake backfill */
  const handleConfirm = () => {
    setStep("confirming");
    setTimeout(()=>{
      setStep("backfill");
      const init = {};
      const submitWallets = validatedWallets.filter(w=>w.state==="ok"||w.state==="warn");
      submitWallets.forEach(w=>{
        const wk = w.addr.trim().slice(0,10);
        nets.forEach(nid=>{ init[`${wk}_${nid}`]=0; });
      });
      setProgress(init);
      /* stagger start per wallet */
      submitWallets.forEach((w,wi)=>{
        const wk = w.addr.trim().slice(0,10);
        nets.forEach((nid,ni)=>{
          const delay = wi*600 + ni*200 + 300;
          const tick = ()=>setProgress(p=>{
            const key=`${wk}_${nid}`;
            if((p[key]||0)>=100) return p;
            const next=Math.min((p[key]||0)+(Math.random()*6+2),100);
            if(next<100) setTimeout(tick,150+Math.random()*300);
            return {...p,[key]:next};
          });
          setTimeout(tick, delay);
        });
      });
    },700);
  };

  const submitWallets = validatedWallets.filter(w=>w.state==="ok"||w.state==="warn");

  return (
    <div style={{
      position:"fixed",inset:0,zIndex:300,
      display:"flex",alignItems:"flex-start",justifyContent:"flex-end",
      padding:"54px 16px 0",
      pointerEvents:"none",
    }}>
      {/* Panel */}
      <div className="fadeDown" style={{
        width:460,
        maxHeight:"calc(100vh - 70px)",
        background:C.surface,
        border:`1px solid ${C.borderHi}`,
        borderRadius:10,
        boxShadow:"0 24px 64px #00000099, 0 0 0 1px #ffffff06",
        pointerEvents:"all",
        display:"flex",flexDirection:"column",
        overflow:"hidden",
      }}>

        {/* ── FORM STEP ── */}
        {(step==="form") && (
          <div style={{display:"flex",flexDirection:"column",overflow:"hidden"}}>
            {/* Header */}
            <div style={{padding:"13px 16px 11px",borderBottom:`1px solid ${C.border}`,flexShrink:0}}>
              <div style={{display:"flex",justifyContent:"space-between",alignItems:"flex-start"}}>
                <div>
                  <div style={{fontWeight:700,fontSize:14,marginBottom:2}}>Add wallets</div>
                  <div style={{fontSize:11,color:C.sub}}>Read-only · no signing required · up to 10 addresses</div>
                </div>
                <button onClick={onClose} style={{padding:4,color:C.sub,borderRadius:4,display:"flex"}}>
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
                </button>
              </div>
            </div>

            <div style={{flex:1,overflowY:"auto",padding:"14px 16px",display:"flex",flexDirection:"column",gap:16}}>
              {/* Wallet rows */}
              <div>
                <SL action={
                  wallets.length<10 && (
                    <button onClick={addRow} style={{display:"flex",alignItems:"center",gap:4,
                      fontSize:10,color:C.cyan,padding:"2px 7px",
                      border:`1px solid ${C.cyanMid}`,borderRadius:4,background:C.cyanDim,fontWeight:600}}>
                      <PlusIcon s={9} c={C.cyan}/> Add row
                    </button>
                  )
                }>
                  Wallet addresses
                  {wallets.length>1&&<span style={{color:C.muted,marginLeft:6,fontSize:8}}>{readyCount}/{wallets.length} valid</span>}
                </SL>

                {/* Column headers */}
                <div style={{display:"grid",gridTemplateColumns:"8px 1fr 96px 26px",gap:6,
                  padding:"0 0 4px",marginBottom:2}}>
                  <div/>
                  <div style={{fontSize:8,color:C.muted,textTransform:"uppercase",letterSpacing:".1em"}}>Address</div>
                  <div style={{fontSize:8,color:C.muted,textTransform:"uppercase",letterSpacing:".1em"}}>Label</div>
                  <div/>
                </div>

                <div style={{display:"flex",flexDirection:"column",gap:6}}>
                  {wallets.map((w,i)=>(
                    <WalletRow
                      key={w.id}
                      entry={w}
                      index={i}
                      onChange={e=>updateRow(i,e)}
                      onRemove={()=>removeRow(i)}
                      canRemove={wallets.length>1}
                      color={WALLET_COLORS[i%WALLET_COLORS.length]}
                    />
                  ))}
                </div>

                {/* Paste hint */}
                <div style={{marginTop:8,fontSize:10,color:C.muted,display:"flex",gap:4,alignItems:"center"}}>
                  <InfoIco s={10} c={C.muted}/>
                  Paste multiple addresses separated by newline or comma — rows auto-expand
                </div>
              </div>

              {/* Network selector */}
              <NetworkSelector selected={nets} onChange={setNets}/>

              {/* Info strip */}
              <div style={{padding:"8px 10px",background:C.cyanDim,border:`1px solid ${C.cyanMid}`,
                borderRadius:6,display:"flex",gap:7,alignItems:"flex-start"}}>
                <InfoIco s={11} c={C.cyan}/>
                <div style={{fontSize:11,color:C.sub,lineHeight:1.55}}>
                  Backfill scans <strong style={{color:C.text}}>~2 years</strong> of transactions per wallet.
                  {" "}Estimated: <span className="mono" style={{color:C.cyan}}>3–8 min</span> per wallet×network.
                  {" "}Partial results appear as indexing runs.
                </div>
              </div>
            </div>

            {/* Footer */}
            <div style={{borderTop:`1px solid ${C.border}`,padding:"10px 16px",
              display:"flex",alignItems:"center",gap:8,flexShrink:0}}>
              {readyCount>0&&(
                <div style={{fontSize:11,color:C.sub,flex:1}}>
                  <span className="mono" style={{color:C.green,fontWeight:700}}>{readyCount}</span>
                  {" "}wallet{readyCount>1?"s":""} · <span className="mono">{nets.length}</span> network{nets.length>1?"s":""}
                </div>
              )}
              <div style={{marginLeft:"auto",display:"flex",gap:8}}>
                <button onClick={onClose}
                  style={{padding:"7px 13px",border:`1px solid ${C.border}`,borderRadius:5,color:C.sub,fontSize:12}}>
                  Cancel
                </button>
                <button onClick={()=>setStep("review")} disabled={!canReview}
                  style={{
                    display:"flex",alignItems:"center",gap:5,
                    padding:"7px 18px",borderRadius:5,fontWeight:700,fontSize:12,
                    background:canReview?C.cyan:"#141828",
                    color:canReview?"#000":C.muted,
                    border:"none",transition:"all .12s",cursor:canReview?"pointer":"not-allowed",
                  }}>
                  Review →
                </button>
              </div>
            </div>
          </div>
        )}

        {/* ── REVIEW STEP ── */}
        {(step==="review"||step==="confirming") && (
          <ReviewStep
            wallets={submitWallets}
            nets={nets}
            onBack={()=>setStep("form")}
            onConfirm={handleConfirm}
            confirming={step==="confirming"}
          />
        )}

        {/* ── BACKFILL STEP ── */}
        {step==="backfill" && (
          <div style={{overflowY:"auto"}}>
            <BackfillStep
              wallets={submitWallets}
              nets={nets}
              progress={progress}
              onClose={onClose}
            />
          </div>
        )}
      </div>
    </div>
  );
};

/* ═══════════════════════════════════════════════════════════
   APP SHELL
═══════════════════════════════════════════════════════════ */
export default function App() {
  const [open,setOpen] = useState(false);

  return (
    <div style={{minHeight:"100vh",background:C.bg}}>
      <style>{G}</style>

      {/* Top bar */}
      <div style={{borderBottom:`1px solid ${C.border}`,background:C.surface,
        display:"flex",alignItems:"center",gap:14,padding:"0 16px",height:46}}>
        <div style={{display:"flex",alignItems:"center",gap:7}}>
          <div style={{width:24,height:24,background:C.cyan,borderRadius:5,
            display:"flex",alignItems:"center",justifyContent:"center",fontSize:12}}>⚡</div>
          <span style={{fontWeight:800,fontSize:13,letterSpacing:"-.02em"}}>WalletRadar</span>
        </div>
        <div style={{flex:1}}/>
        {/* Existing wallet badges */}
        {[
          {addr:"0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045",label:"Main",  color:C.cyan},
          {addr:"0x47ac0Fb4F2D84898e4D9E7b4DaB3C24507a6D503",label:"DeFi",  color:C.purple},
        ].map(w=>(
          <div key={w.addr} style={{display:"flex",alignItems:"center",gap:4,padding:"3px 8px",
            background:C.hi,border:`1px solid ${C.border}`,borderRadius:5}}>
            <div style={{width:6,height:6,borderRadius:"50%",background:w.color}}/>
            <span className="mono" style={{fontSize:9,color:C.sub}}>{short(w.addr)}</span>
            <span style={{fontSize:9,color:C.sub}}>· {w.label}</span>
          </div>
        ))}
        <button onClick={()=>setOpen(o=>!o)} style={{
          display:"flex",alignItems:"center",gap:5,padding:"5px 12px",
          background:open?C.cyanMid:C.cyan,
          color:open?"#9fecf7":"#000",
          border:`1px solid ${open?C.cyan+"88":"transparent"}`,
          borderRadius:5,fontWeight:700,fontSize:11,transition:"all .12s",
        }}>
          <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round">
            {open?<><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></>:<><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></>}
          </svg>
          {open?"Close":"Add wallets"}
        </button>
      </div>

      {/* Modal */}
      {open && <AddWalletModal onClose={()=>setOpen(false)}/>}

      {/* Hint */}
      {!open && (
        <div style={{display:"flex",alignItems:"center",justifyContent:"center",height:"calc(100vh - 46px)"}}>
          <div style={{textAlign:"center",display:"flex",flexDirection:"column",alignItems:"center",gap:12}}>
            <div style={{fontSize:13,color:C.sub}}>Click <strong style={{color:C.cyan}}>Add wallets</strong> →</div>
            <div style={{display:"flex",flexDirection:"column",gap:5,fontSize:11,color:C.muted,maxWidth:340,textAlign:"left"}}>
              <div>① Add multiple rows with <strong style={{color:C.sub}}>+ Add row</strong></div>
              <div>② Or paste several addresses at once (newline / comma separated)</div>
              <div>③ Each row validates independently — invalid rows are skipped</div>
              <div>④ Try pasting an existing address to see the duplicate warning</div>
              <div>⑤ Review → Start backfill → watch per-wallet×network progress</div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
