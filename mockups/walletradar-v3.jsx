import { useState, useRef } from "react";

/* ═══ TOKENS ═══════════════════════════════════════════════ */
const C = {
  bg:"#07090f", surface:"#0b0d18", hi:"#0f1220", hover:"#121628",
  border:"#181d30", borderHi:"#222840",
  cyan:"#22d3ee", cyanDim:"#22d3ee14", cyanMid:"#22d3ee38",
  green:"#34d399", greenDim:"#34d39914", greenMid:"#34d39938",
  red:"#f87171",  redDim:"#f8717114",
  amber:"#fbbf24",amberDim:"#fbbf2414",amberMid:"#fbbf2438",
  purple:"#a78bfa",purpleDim:"#a78bfa14",purpleMid:"#a78bfa38",
  blue:"#60a5fa", blueDim:"#60a5fa14",
  text:"#dde3f0", sub:"#4a5878", muted:"#252e42",
};

const G = `
@import url('https://fonts.googleapis.com/css2?family=IBM+Plex+Mono:wght@400;500;600&family=DM+Sans:wght@400;500;600;700&display=swap');
*{box-sizing:border-box;margin:0;padding:0}
html,body,#root{height:100%;overflow:hidden}
body{background:${C.bg};color:${C.text};font-family:'DM Sans',sans-serif;font-size:13px;line-height:1.5}
.mono{font-family:'IBM Plex Mono',monospace}
::-webkit-scrollbar{width:3px;height:3px}
::-webkit-scrollbar-track{background:transparent}
::-webkit-scrollbar-thumb{background:${C.borderHi};border-radius:2px}
button,input,select,textarea{font-family:'DM Sans',sans-serif;border:none;background:none;color:${C.text};outline:none}
button{cursor:pointer}
select option{background:${C.hi};color:${C.text}}
@keyframes fadeIn{from{opacity:0;transform:translateY(4px)}to{opacity:1;transform:translateY(0)}}
@keyframes expandIn{from{opacity:0;max-height:0}to{opacity:1;max-height:600px}}
@keyframes spin{to{transform:rotate(360deg)}}
@keyframes pulse{0%,100%{opacity:1}50%{opacity:.2}}
.aFade{animation:fadeIn .18s ease both}
.aExpand{animation:expandIn .22s ease both}

/* Row hover — ONLY the summary row, not the expanded panel */
.tx-summary:hover { background:${C.hover}!important; }

.ifield{width:100%;background:${C.hi};border:1px solid ${C.border};border-radius:5px;
  color:${C.text};padding:6px 9px;font-size:11px;transition:border-color .12s;font-family:'IBM Plex Mono',monospace}
.ifield:focus{border-color:${C.cyanMid}}
.iselect{appearance:none;width:100%;background:${C.hi};border:1px solid ${C.border};
  border-radius:5px;color:${C.text};padding:6px 24px 6px 9px;font-size:11px;cursor:pointer}

.toggle{position:relative;display:inline-block;width:28px;height:16px;flex-shrink:0}
.toggle input{opacity:0;width:0;height:0}
.tslider{position:absolute;inset:0;background:${C.border};border-radius:8px;transition:.2s;cursor:pointer}
.tslider:before{content:'';position:absolute;width:10px;height:10px;left:3px;top:3px;
  background:${C.sub};border-radius:50%;transition:.2s}
input:checked+.tslider{background:${C.cyanMid}}
input:checked+.tslider:before{transform:translateX(12px);background:${C.cyan}}
`;

/* ═══ ICONS ═════════════════════════════════════════════════ */
const Ic = ({d,s=13,c="currentColor",f="none",sw=1.6})=>(
  <svg width={s} height={s} viewBox="0 0 24 24" fill={f} stroke={c} strokeWidth={sw}
    strokeLinecap="round" strokeLinejoin="round" style={{flexShrink:0}}>{d}</svg>
);
const DEFS = {
  overview:<><rect x="3" y="3" width="7" height="7" rx="1"/><rect x="14" y="3" width="7" height="7" rx="1"/><rect x="3" y="14" width="7" height="7" rx="1"/><rect x="14" y="14" width="7" height="7" rx="1"/></>,
  tokens:  <><circle cx="12" cy="12" r="9"/><path d="M9 12h6M12 9v6"/></>,
  lp:      <><path d="M3 3l7.07 16.97 2.51-7.39 7.39-2.51L3 3z"/><path d="M13 13l6 6"/></>,
  lending: <><path d="M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/><polyline points="9 22 9 12 15 12 15 22"/></>,
  staking: <><polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/></>,
  wallet:  <><rect x="2" y="7" width="20" height="14" rx="2"/><path d="M16 3l-4 4-4-4"/></>,
  warn:    <><path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/><line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/></>,
  edit:    <><path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/><path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/></>,
  x:       <><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></>,
  check:   <polyline points="20 6 9 17 4 12"/>,
  clock:   <><circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/></>,
  plus:    <><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></>,
  trash:   <><polyline points="3 6 5 6 21 6"/><path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"/><path d="M10 11v6M14 11v6"/></>,
  info:    <><circle cx="12" cy="12" r="10"/><line x1="12" y1="16" x2="12" y2="12"/><line x1="12" y1="8" x2="12.01" y2="8"/></>,
  chev_d:  <polyline points="6 9 12 15 18 9"/>,
  chev_u:  <polyline points="18 15 12 9 6 15"/>,
  arr_u:   <><line x1="12" y1="19" x2="12" y2="5"/><polyline points="5 12 12 5 19 12"/></>,
  arr_d:   <><line x1="12" y1="5" x2="12" y2="19"/><polyline points="19 12 12 19 5 12"/></>,
  filter:  <><polygon points="22 3 2 3 10 12.46 10 19 14 21 14 12.46 22 3"/></>,
  search:  <><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></>,
  spinner: null,
};
const Icon = ({n,s=13,c="currentColor",f="none"}) => <Ic d={DEFS[n]} s={s} c={c} f={f}/>;
const Spin = ({s=13,c=C.cyan}) => (
  <svg width={s} height={s} viewBox="0 0 24 24" style={{animation:"spin .7s linear infinite",flexShrink:0}}>
    <circle cx="12" cy="12" r="10" fill="none" stroke={c+"33"} strokeWidth="3"/>
    <path d="M12 2a10 10 0 0 1 10 10" fill="none" stroke={c} strokeWidth="3" strokeLinecap="round"/>
  </svg>
);

/* ═══ PRIMITIVES ════════════════════════════════════════════ */
const Pill = ({children,v="def"}) => {
  const vs={def:{bg:C.hi,c:C.sub,b:C.border},cyan:{bg:C.cyanDim,c:C.cyan,b:C.cyanMid},
    green:{bg:C.greenDim,c:C.green,b:C.greenMid},red:{bg:C.redDim,c:C.red,b:C.red+"44"},
    amber:{bg:C.amberDim,c:C.amber,b:C.amberMid},purple:{bg:C.purpleDim,c:C.purple,b:C.purpleMid},
    blue:{bg:C.blueDim,c:C.blue,b:C.blue+"44"}};
  const s=vs[v]||vs.def;
  return <span style={{display:"inline-flex",alignItems:"center",gap:3,padding:"1px 6px",borderRadius:3,
    fontSize:9,fontWeight:700,fontFamily:"'IBM Plex Mono',monospace",letterSpacing:".04em",
    background:s.bg,color:s.c,border:`1px solid ${s.b}`}}>{children}</span>;
};
const SL = ({children,mb=6}) => (
  <div style={{fontSize:9,fontWeight:700,color:C.muted,textTransform:"uppercase",letterSpacing:".12em",marginBottom:mb}}>{children}</div>
);
const Divider = () => <div style={{borderTop:`1px solid ${C.border}`,margin:"8px 0"}}/>;
const PBar = ({v,c=C.cyan,h=3}) => (
  <div style={{background:C.border,borderRadius:h,height:h,overflow:"hidden",flex:1}}>
    <div style={{width:`${Math.min(v,100)}%`,height:"100%",background:c,borderRadius:h,transition:"width .4s"}}/>
  </div>
);
const fmt$ = n => { const a=Math.abs(n); const s=a>=1000?`$${(a/1000).toFixed(1)}k`:`$${a.toFixed(2)}`; return (n<0?"-":"")+s; };
const fmtPct = n => `${n>=0?"+":""}${n.toFixed(1)}%`;
const short = a => `${a.slice(0,6)}…${a.slice(-4)}`;

/* ═══ DATA ══════════════════════════════════════════════════ */
const WALLETS = [
  {id:"w1",label:"Main", addr:"0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045",color:C.cyan},
  {id:"w2",label:"DeFi", addr:"0x47ac0Fb4F2D84898e4D9E7b4DaB3C24507a6D503",color:C.purple},
];
const NETS = [
  {id:"ETH",icon:"⟠",label:"Ethereum",color:"#627EEA"},
  {id:"ARB",icon:"△",label:"Arbitrum",color:"#28A0F0"},
  {id:"BASE",icon:"◆",label:"Base",color:"#0052FF"},
  {id:"OP",icon:"○",label:"Optimism",color:"#FF0420"},
  {id:"POL",icon:"⬡",label:"Polygon",color:"#7B3FE4"},
];
const TX_TYPES = [
  "SWAP","EXTERNAL_INBOUND","EXTERNAL_TRANSFER_OUT",
  "LP_ENTRY","LP_EXIT","LP_EXIT_PARTIAL","LP_EXIT_FINAL","LP_FEE_CLAIM",
  "LP_POSITION_STAKE","LP_POSITION_UNSTAKE",
  "LEND_DEPOSIT","LEND_WITHDRAWAL","BORROW","REPAY",
  "STAKE_DEPOSIT","STAKE_WITHDRAWAL","MANUAL_COMPENSATING",
];
const PRICE_SOURCES = ["STABLECOIN","SWAP_DERIVED","COINGECKO","MANUAL","UNKNOWN"];
const SRC_COLOR = {STABLECOIN:C.green,SWAP_DERIVED:C.cyan,COINGECKO:C.purple,MANUAL:C.amber,UNKNOWN:C.red};

const TOKENS = [
  {sym:"ETH", name:"Ethereum",   qty:2.4831, price:3241.2, avco:2180,  uPnl:48.7,  uPnlUsd:2636.9, rPnl:1840,  net:"ETH", w:"w1",issue:null},
  {sym:"WBTC",name:"Wrapped BTC",qty:0.0815, price:62400,  avco:48200, uPnl:29.5,  uPnlUsd:1157.1, rPnl:420,   net:"ARB", w:"w2",issue:null},
  {sym:"ARB", name:"Arbitrum",   qty:4200,   price:0.882,  avco:1.24,  uPnl:-28.9, uPnlUsd:-1512,  rPnl:-380,  net:"ARB", w:"w2",issue:"recon"},
  {sym:"USDC",name:"USD Coin",   qty:1850,   price:1,      avco:1,     uPnl:0,     uPnlUsd:0,      rPnl:0,     net:"BASE",w:"w1",issue:null},
  {sym:"OP",  name:"Optimism",   qty:820,    price:1.42,   avco:2.10,  uPnl:-32.4, uPnlUsd:-557.6, rPnl:-120,  net:"OP",  w:"w1",issue:null},
  {sym:"MATIC",name:"Polygon",   qty:1200,   price:0.58,   avco:0.92,  uPnl:-37,   uPnlUsd:-408,   rPnl:0,     net:"POL", w:"w2",issue:null},
  {sym:"PEPE",name:"Pepe",       qty:12e6,   price:0.0000089,avco:0.000015,uPnl:-40.7,uPnlUsd:-72, rPnl:-15,   net:"ETH", w:"w1",issue:null},
];
const LP_POSITIONS = [
  {id:"lp1",protocol:"Uniswap V3",pair:"ETH/USDC",range:"$2800–$3600",status:"open",
   value:4820,fees:142.4,il:-2.3,ilUsd:-112,pnl:30.1,pnlUsd:1448,entered:"2024-10-12",net:"ETH",w:"w1",nftId:"#841022"},
  {id:"lp2",protocol:"Aerodrome", pair:"WBTC/ETH",range:"Full range",  status:"open",
   value:2310,fees:88.6, il:-0.8,ilUsd:-18, pnl:14.2,pnlUsd:327, entered:"2024-12-01",net:"BASE",w:"w2",nftId:null},
  {id:"lp3",protocol:"Uniswap V3",pair:"ARB/USDC",range:"$0.80–$1.40",status:"closed",
   value:0,   fees:34.1, il:-8.1,ilUsd:-192,pnl:-4.2,pnlUsd:-158,entered:"2024-06-14",exited:"2024-10-30",net:"ARB",w:"w2",nftId:"#503211"},
];
const LENDING = [
  {id:"ld1",protocol:"Aave V3",  type:"deposit",asset:"ETH", qty:1.2,  value:3889.4,apy:3.2, earned:34.2, net:"ETH",w:"w1"},
  {id:"ld2",protocol:"Aave V3",  type:"borrow", asset:"USDC",qty:2000, value:2000,  apy:-5.1,interest:-48,net:"ETH",w:"w1"},
  {id:"ld3",protocol:"Compound", type:"deposit",asset:"WBTC",qty:0.04, value:2496,  apy:1.8, earned:12.1, net:"ETH",w:"w2"},
  {id:"ld4",protocol:"Aave V3",  type:"borrow", asset:"DAI", qty:800,  value:800,   apy:-4.8,interest:-22,net:"OP", w:"w2"},
];
const TXS = [
  {id:"t1",hash:"0xabc…d4f1",ts:"2025-03-12 14:32",type:"SWAP",            sym:"ETH", net:"ETH",w:"w1",status:"CONFIRMED",    issue:null,
   flows:[{role:"BUY",sym:"ETH",qty:"0.8200",price:"2841.20",src:"SWAP_DERIVED"},{role:"SELL",sym:"USDC",qty:"2329.78",price:"1.00",src:"STABLECOIN"}]},
  {id:"t2",hash:"0x912…ff02",ts:"2025-02-28 09:15",type:"EXTERNAL_INBOUND",sym:"ARB", net:"ARB",w:"w2",status:"CONFIRMED",    issue:"missing_price",
   flows:[{role:"BUY",sym:"ARB",qty:"1500",price:null,src:"UNKNOWN"}]},
  {id:"t3",hash:"0x44c…8812",ts:"2025-01-07 17:50",type:"SWAP",            sym:"WBTC",net:"ETH",w:"w1",status:"CONFIRMED",    issue:null,hasOverride:true,
   flows:[{role:"BUY",sym:"WBTC",qty:"0.0412",price:"43200.00",src:"COINGECKO"},{role:"SELL",sym:"ETH",qty:"0.5800",price:"3100.00",src:"SWAP_DERIVED"}]},
  {id:"t4",hash:"0x77f…3310",ts:"2024-12-20 11:04",type:"SWAP",            sym:"OP",  net:"OP", w:"w1",status:"PENDING_PRICE",issue:"unconfirmed",
   flows:[{role:"BUY",sym:"OP",qty:"200",price:null,src:"UNKNOWN"},{role:"SELL",sym:"USDC",qty:"420.00",price:"1.00",src:"STABLECOIN"}]},
  {id:"t5",hash:"0xc91…ab44",ts:"2024-12-01 08:22",type:"LP_ENTRY",        sym:"ETH", net:"BASE",w:"w2",status:"CONFIRMED",   issue:null,
   flows:[{role:"SELL",sym:"ETH",qty:"0.44",price:"3920.00",src:"COINGECKO"},{role:"SELL",sym:"USDC",qty:"1724.80",price:"1.00",src:"STABLECOIN"}]},
  {id:"t6",hash:"0x02d…9f12",ts:"2024-11-15 20:18",type:"LEND_DEPOSIT",    sym:"ETH", net:"ETH",w:"w1",status:"CONFIRMED",    issue:null,
   flows:[{role:"SELL",sym:"ETH",qty:"1.2",price:"3800.00",src:"COINGECKO"}]},
  {id:"t7",hash:"0x3fa…cc01",ts:"2024-10-08 06:55",type:"EXTERNAL_INBOUND",sym:"PEPE",net:"ETH",w:"w1",status:"NEEDS_REVIEW", issue:"missing_price",
   flows:[{role:"BUY",sym:"PEPE",qty:"12000000",price:null,src:"UNKNOWN"}]},
];

/* ═══ TX INLINE EDITOR ══════════════════════════════════════ */
const TxInlineEditor = ({tx, onSave, onCancel}) => {
  const [type,  setType]  = useState(tx.type);
  const [ts,    setTs]    = useState(tx.ts);
  const [flows, setFlows] = useState(tx.flows.map((f,i)=>({...f,_id:i})));
  const [note,  setNote]  = useState(tx.note||"");
  const [saving,setSaving]= useState(false);
  const [saved, setSaved] = useState(false);

  const updFlow = (i,k,v)=>{ const f=[...flows]; f[i]={...f[i],[k]:v}; setFlows(f); };
  const addFlow = ()=>setFlows([...flows,{_id:Date.now(),role:"BUY",sym:"",qty:"",price:"",src:"MANUAL"}]);
  const delFlow = i=>setFlows(flows.filter((_,j)=>j!==i));

  const roleC={BUY:C.green,SELL:C.red,FEE:C.amber,TRANSFER:C.sub};
  const changed = type!==tx.type || ts!==tx.ts || note;
  const hasMissing = flows.some(f=>!f.price);

  const save=()=>{
    setSaving(true);
    setTimeout(()=>{setSaving(false);setSaved(true);
      setTimeout(()=>onSave({...tx,type,flows,ts,note}),500);},900);
  };

  return (
    <div className="aExpand" style={{
      background:C.surface,borderTop:`1px solid ${C.borderHi}`,
      borderBottom:`2px solid ${C.cyanMid}`,
      padding:"14px 16px",display:"flex",flexDirection:"column",gap:12,
    }}>
      {/* Recalc warning */}
      <div style={{display:"flex",gap:7,alignItems:"flex-start",padding:"7px 10px",
        background:C.amberDim,border:`1px solid ${C.amberMid}`,borderRadius:5}}>
        <Icon n="warn" s={11} c={C.amber}/>
        <span style={{fontSize:11,color:C.amber,lineHeight:1.5}}>
          Changing type or flows triggers full AVCO recalculation from this point forward.
        </span>
      </div>

      <div style={{display:"grid",gridTemplateColumns:"1fr 1fr",gap:10}}>
        {/* Type */}
        <div>
          <SL mb={4}>Transaction type</SL>
          <div style={{position:"relative"}}>
            <select className="iselect" value={type} onChange={e=>setType(e.target.value)}>
              {TX_TYPES.map(t=><option key={t} value={t}>{t.replace(/_/g," ")}</option>)}
            </select>
            <div style={{position:"absolute",right:7,top:"50%",transform:"translateY(-50%)",pointerEvents:"none"}}>
              <Icon n="chev_d" s={10} c={C.sub}/>
            </div>
          </div>
          {type!==tx.type&&<div style={{fontSize:9,color:C.amber,marginTop:3,display:"flex",gap:3,alignItems:"center"}}>
            <Icon n="warn" s={9} c={C.amber}/> was: {tx.type}</div>}
        </div>
        {/* Timestamp */}
        <div>
          <SL mb={4}>Block timestamp</SL>
          <input className="ifield" value={ts} onChange={e=>setTs(e.target.value)} style={{fontSize:11}}/>
        </div>
      </div>

      {/* Flows */}
      <div>
        <div style={{display:"flex",justifyContent:"space-between",alignItems:"center",marginBottom:6}}>
          <SL mb={0}>Flows</SL>
          <button onClick={addFlow} style={{display:"flex",alignItems:"center",gap:3,padding:"2px 7px",
            border:`1px solid ${C.cyanMid}`,borderRadius:4,background:C.cyanDim,color:C.cyan,fontSize:10,fontWeight:600}}>
            <Icon n="plus" s={9} c={C.cyan}/> Add flow
          </button>
        </div>
        <div style={{display:"flex",flexDirection:"column",gap:6}}>
          {flows.map((fl,i)=>(
            <div key={fl._id} style={{background:C.hi,border:`1px solid ${C.border}`,borderRadius:6,padding:"9px 10px"}}>
              {/* Role selector */}
              <div style={{display:"flex",alignItems:"center",gap:6,marginBottom:7}}>
                <div style={{display:"flex",gap:3}}>
                  {["BUY","SELL","FEE","TRANSFER"].map(r=>(
                    <button key={r} onClick={()=>updFlow(i,"role",r)} style={{
                      padding:"2px 7px",borderRadius:3,fontSize:9,fontWeight:700,letterSpacing:".04em",
                      background:fl.role===r?roleC[r]+"22":"transparent",
                      border:`1px solid ${fl.role===r?roleC[r]+"55":C.border}`,
                      color:fl.role===r?roleC[r]:C.sub,
                    }}>{r}</button>
                  ))}
                </div>
                <div style={{flex:1}}/>
                {flows.length>1&&(
                  <button onClick={()=>delFlow(i)} style={{color:C.sub,padding:2,display:"flex",borderRadius:3}}>
                    <Icon n="trash" s={11}/>
                  </button>
                )}
              </div>
              {/* Fields grid */}
              <div style={{display:"grid",gridTemplateColumns:"1fr 1fr 1fr 1fr",gap:6}}>
                {[
                  {label:"Asset",    val:fl.sym,   key:"sym",   ph:"ETH"},
                  {label:"Quantity", val:fl.qty,   key:"qty",   ph:"0.0000"},
                  {label:"Price USD",val:fl.price||"",key:"price",ph:"0.00",warn:!fl.price},
                  {label:"Source",   val:fl.src,   key:"src",   isSelect:true},
                ].map(f=>(
                  <div key={f.key}>
                    <div style={{fontSize:8,color:C.muted,textTransform:"uppercase",letterSpacing:".1em",marginBottom:3}}>
                      {f.label}{f.warn&&<span style={{color:C.amber}}> !</span>}
                    </div>
                    {f.isSelect ? (
                      <div style={{position:"relative"}}>
                        <select className="iselect" value={fl.src}
                          onChange={e=>updFlow(i,"src",e.target.value)}
                          style={{fontSize:10,padding:"5px 20px 5px 7px",color:SRC_COLOR[fl.src]||C.text}}>
                          {PRICE_SOURCES.map(s=><option key={s} value={s}>{s}</option>)}
                        </select>
                        <div style={{position:"absolute",right:5,top:"50%",transform:"translateY(-50%)",pointerEvents:"none"}}>
                          <Icon n="chev_d" s={9} c={C.sub}/>
                        </div>
                      </div>
                    ):(
                      <input className="ifield"
                        value={f.val} placeholder={f.ph}
                        onChange={e=>updFlow(i,f.key,e.target.value)}
                        style={{fontSize:10,borderColor:f.warn?C.amber+"66":C.border}}/>
                    )}
                  </div>
                ))}
              </div>
              {/* Preview */}
              {fl.qty&&fl.price&&(
                <div className="mono" style={{fontSize:9,color:C.sub,marginTop:5}}>
                  ≈ {fmt$(parseFloat(fl.qty||0)*parseFloat(fl.price||0))}
                  {" · "}<span style={{color:SRC_COLOR[fl.src]}}>{fl.src}</span>
                </div>
              )}
              {!fl.price&&(
                <div style={{fontSize:9,color:C.amber,marginTop:4,display:"flex",gap:3,alignItems:"center"}}>
                  <Icon n="warn" s={9} c={C.amber}/> No price — AVCO will use qty only
                </div>
              )}
            </div>
          ))}
        </div>
      </div>

      {/* Note */}
      <div>
        <SL mb={4}>Note (optional)</SL>
        <input className="ifield" value={note} onChange={e=>setNote(e.target.value)}
          placeholder="Reason for edit…" style={{fontSize:11}}/>
      </div>

      {/* Actions */}
      <div style={{display:"flex",alignItems:"center",gap:8,justifyContent:"flex-end",paddingTop:2}}>
        {hasMissing&&(
          <div style={{fontSize:10,color:C.amber,flex:1,display:"flex",gap:4,alignItems:"center"}}>
            <Icon n="warn" s={10} c={C.amber}/> {flows.filter(f=>!f.price).length} flow(s) missing price
          </div>
        )}
        <button onClick={onCancel} style={{padding:"5px 12px",border:`1px solid ${C.border}`,
          borderRadius:5,color:C.sub,fontSize:11,fontWeight:500}}>Cancel</button>
        <button onClick={save} disabled={saving||saved} style={{
          display:"flex",alignItems:"center",gap:5,padding:"5px 14px",
          background:saved?C.greenDim:C.cyan,color:saved?C.green:"#000",
          border:`1px solid ${saved?C.green+"55":"transparent"}`,
          borderRadius:5,fontWeight:700,fontSize:11,minWidth:110,justifyContent:"center",
        }}>
          {saving?<><Spin s={12} c="#000"/> Saving…</>
          :saved?<><Icon n="check" s={12} c={C.green}/> Saved!</>
          :"Save & Recalc"}
        </button>
      </div>
    </div>
  );
};

/* ═══ TX ROW ════════════════════════════════════════════════ */
const TxRow = ({tx}) => {
  const [expanded, setExpanded] = useState(false);
  const [editing,  setEditing]  = useState(false);
  const [data,     setData]     = useState(tx);

  const net    = NETS.find(n=>n.id===data.net);
  const wallet = WALLETS.find(w=>w.id===data.w);
  const buy    = data.flows.find(f=>f.role==="BUY");
  const sell   = data.flows.find(f=>f.role==="SELL");
  const hasMissing = data.flows.some(f=>!f.price);
  const isUnconf   = data.status==="PENDING_PRICE"||data.status==="NEEDS_REVIEW";

  const statusPillV = {CONFIRMED:"green",PENDING_PRICE:"amber",NEEDS_REVIEW:"red"}[data.status]||"def";

  const issueColor = hasMissing?C.amber : isUnconf?C.purple : null;
  const issueIcon  = hasMissing?"warn"  : isUnconf?"clock"  : null;

  const handleSave = updated => { setData(updated); setEditing(false); };

  return (
    <div style={{borderBottom:`1px solid ${C.border}`}}>
      {/* Summary row */}
      <div
        className="tx-summary"
        onClick={()=>{ if(!editing){setExpanded(e=>!e);} }}
        style={{
          display:"grid",
          gridTemplateColumns:"18px 1fr 120px 100px 80px 56px",
          gap:6,padding:"7px 12px",alignItems:"center",
          cursor:"pointer",
          background: expanded ? C.hover : "transparent",
          borderLeft: `2px solid ${expanded ? C.cyanMid : "transparent"}`,
          transition:"background .1s, border-color .1s",
        }}
      >
        {/* Status / issue indicator */}
        <div style={{display:"flex",justifyContent:"center"}}>
          {issueIcon
            ? <Icon n={issueIcon} s={12} c={issueColor}/>
            : <div style={{width:5,height:5,borderRadius:"50%",background:C.green+"88"}}/>
          }
        </div>

        {/* Type + hash */}
        <div style={{minWidth:0}}>
          <div style={{fontWeight:600,fontSize:11,display:"flex",alignItems:"center",gap:5,flexWrap:"wrap"}}>
            <span style={{overflow:"hidden",textOverflow:"ellipsis",whiteSpace:"nowrap"}}>{data.type.replace(/_/g," ")}</span>
            {data.hasOverride&&<Pill v="purple">OVR</Pill>}
            {hasMissing&&<Pill v="amber">PRICE?</Pill>}
            {isUnconf&&<Pill v="purple">PENDING</Pill>}
          </div>
          <div className="mono" style={{fontSize:9,color:C.muted,marginTop:1}}>{data.hash}</div>
        </div>

        {/* Flows summary */}
        <div style={{display:"flex",flexDirection:"column",gap:1}}>
          {buy&&<div style={{fontSize:10,fontWeight:600,color:C.green,fontFamily:"'IBM Plex Mono',monospace"}}>
            +{buy.qty} {buy.sym} {buy.price?<span style={{color:C.sub,fontWeight:400}}>${buy.price}</span>:<span style={{color:C.amber}}>?</span>}
          </div>}
          {sell&&<div style={{fontSize:10,fontWeight:600,color:C.red,fontFamily:"'IBM Plex Mono',monospace"}}>
            −{sell.qty} {sell.sym}
          </div>}
        </div>

        {/* Meta */}
        <div style={{display:"flex",flexDirection:"column",gap:2,alignItems:"flex-start"}}>
          <div style={{display:"flex",gap:4,alignItems:"center"}}>
            <span style={{fontSize:9,color:net?.color}}>{net?.icon}</span>
            <div style={{width:5,height:5,borderRadius:"50%",background:wallet?.color,flexShrink:0}}/>
            <span className="mono" style={{fontSize:9,color:C.muted}}>{wallet?.label}</span>
          </div>
          <Pill v={statusPillV}>{data.status.replace(/_/g," ")}</Pill>
        </div>

        {/* Date */}
        <div className="mono" style={{fontSize:9,color:C.muted,textAlign:"right"}}>
          {data.ts.split(" ")[0]}
        </div>

        {/* Edit button */}
        <div style={{display:"flex",justifyContent:"flex-end"}} onClick={e=>e.stopPropagation()}>
          <button
            onClick={()=>{ setExpanded(true); setEditing(e=>!e); }}
            style={{display:"flex",alignItems:"center",gap:3,padding:"3px 7px",
              border:`1px solid ${editing?C.cyanMid:C.border}`,borderRadius:4,
              background:editing?C.cyanDim:C.hi,
              color:editing?C.cyan:C.sub,fontSize:10,fontWeight:600,
            }}>
            <Icon n="edit" s={9} c={editing?C.cyan:C.sub}/>
            {editing?"Editing":"Edit"}
          </button>
        </div>
      </div>

      {/* Expanded read-only view */}
      {expanded && !editing && (
        <div className="aExpand" style={{
          background:C.surface,borderTop:`1px solid ${C.border}`,
          padding:"10px 14px",display:"flex",flexDirection:"column",gap:8,
        }}>
          {/* Flows detail */}
          <div style={{display:"flex",gap:8}}>
            {data.flows.map((fl,i)=>{
              const rc={BUY:C.green,SELL:C.red,FEE:C.amber,TRANSFER:C.sub}[fl.role];
              return (
                <div key={i} style={{flex:1,minWidth:130,padding:"7px 10px",
                  background:fl.role==="BUY"?C.greenDim:fl.role==="SELL"?C.redDim:C.amberDim,
                  border:`1px solid ${rc}22`,borderRadius:6}}>
                  <div style={{fontSize:8,fontWeight:700,color:rc,textTransform:"uppercase",letterSpacing:".08em",marginBottom:4}}>
                    {fl.role==="BUY"?"↑ Received":fl.role==="SELL"?"↓ Sent":"◆ "+fl.role}
                  </div>
                  <div className="mono" style={{fontSize:13,fontWeight:700}}>
                    {fl.role==="BUY"?"+":"-"}{fl.qty} {fl.sym}
                  </div>
                  <div style={{display:"flex",justifyContent:"space-between",alignItems:"center",marginTop:4}}>
                    {fl.price
                      ?<span className="mono" style={{fontSize:10,color:C.sub}}>${fl.price}</span>
                      :<span style={{fontSize:10,color:C.amber,display:"flex",gap:3,alignItems:"center"}}>
                        <Icon n="warn" s={9} c={C.amber}/> No price
                      </span>
                    }
                    <span style={{fontSize:9,fontWeight:700,fontFamily:"'IBM Plex Mono',monospace",
                      color:SRC_COLOR[fl.src]||C.sub,
                      background:SRC_COLOR[fl.src]+"18",padding:"1px 5px",borderRadius:3,
                      border:`1px solid ${SRC_COLOR[fl.src]+"33"}`}}>{fl.src}</span>
                  </div>
                </div>
              );
            })}
          </div>

          {/* Missing price CTA */}
          {hasMissing&&(
            <div style={{padding:"7px 10px",background:C.amberDim,border:`1px solid ${C.amberMid}`,
              borderRadius:5,display:"flex",justifyContent:"space-between",alignItems:"center"}}>
              <div style={{fontSize:11,color:C.amber}}>Missing price — AVCO calculated without value. Override to fix cost basis.</div>
              <button onClick={()=>setEditing(true)} style={{display:"flex",alignItems:"center",gap:4,
                padding:"4px 10px",background:C.amberMid,border:`1px solid ${C.amber}44`,
                borderRadius:4,color:C.amber,fontSize:10,fontWeight:600}}>
                <Icon n="edit" s={10} c={C.amber}/> Set price
              </button>
            </div>
          )}
          {data.hasOverride&&(
            <div style={{padding:"6px 10px",background:C.purpleDim,border:`1px solid ${C.purpleMid}`,
              borderRadius:5,display:"flex",justifyContent:"space-between",alignItems:"center"}}>
              <div style={{fontSize:11,color:C.purple}}>Manual override active</div>
              <button style={{padding:"3px 8px",border:`1px solid ${C.purpleMid}`,borderRadius:4,
                color:C.purple,fontSize:10,background:"transparent"}}>Revert</button>
            </div>
          )}
        </div>
      )}

      {/* Inline editor */}
      {expanded && editing && (
        <TxInlineEditor
          tx={data}
          onSave={handleSave}
          onCancel={()=>{ setEditing(false); }}
        />
      )}
    </div>
  );
};

/* ═══ TRANSACTIONS PANEL ════════════════════════════════════ */
const TxPanel = ({fW,fN}) => {
  const [search,setSearch]=useState("");
  const rows=TXS.filter(tx=>{
    if(fW.length&&!fW.includes(tx.w))return false;
    if(fN.length&&!fN.includes(tx.net))return false;
    if(search&&!tx.hash.includes(search)&&!tx.sym.toLowerCase().includes(search.toLowerCase()))return false;
    return true;
  });
  return (
    <div style={{display:"flex",flexDirection:"column",height:"100%",borderLeft:`1px solid ${C.border}`}}>
      {/* Header */}
      <div style={{padding:"6px 12px",borderBottom:`1px solid ${C.border}`,display:"flex",alignItems:"center",gap:8,flexShrink:0}}>
        <span style={{fontSize:11,fontWeight:700,color:C.sub}}>Transactions</span>
        <div style={{position:"relative",flex:1,maxWidth:220}}>
          <input value={search} onChange={e=>setSearch(e.target.value)}
            placeholder="Search hash or symbol…"
            style={{width:"100%",background:C.hi,border:`1px solid ${C.border}`,borderRadius:5,
              color:C.text,padding:"4px 8px 4px 24px",fontSize:10,fontFamily:"'IBM Plex Mono',monospace"}}/>
          <div style={{position:"absolute",left:7,top:"50%",transform:"translateY(-50%)",pointerEvents:"none"}}>
            <Icon n="search" s={10} c={C.sub}/>
          </div>
        </div>
        <span className="mono" style={{marginLeft:"auto",fontSize:9,color:C.muted}}>{rows.length} txns</span>
      </div>

      {/* Column headers */}
      <div style={{display:"grid",gridTemplateColumns:"18px 1fr 120px 100px 80px 56px",
        gap:6,padding:"4px 12px",borderBottom:`1px solid ${C.border}`,flexShrink:0}}>
        {["","Type / Hash","Flows","Network","Date",""].map((h,i)=>(
          <div key={i} style={{fontSize:8,color:C.muted,textTransform:"uppercase",letterSpacing:".1em",
            textAlign:i===4||i===5?"right":"left"}}>{h}</div>
        ))}
      </div>

      <div style={{flex:1,overflowY:"auto"}}>
        {rows.map(tx=><TxRow key={tx.id} tx={tx}/>)}
        {rows.length===0&&(
          <div style={{padding:32,textAlign:"center",color:C.sub,fontSize:12}}>No transactions match</div>
        )}
      </div>
    </div>
  );
};

/* ═══ SECTION: TOKENS ═══════════════════════════════════════ */
const STokens = ({fW,fN,hide}) => {
  const rows=TOKENS.filter(a=>{
    if(hide&&a.qty*a.price<0.5)return false;
    if(fW.length&&!fW.includes(a.w))return false;
    if(fN.length&&!fN.includes(a.net))return false;
    return true;
  });
  return (
    <div style={{display:"flex",flexDirection:"column",height:"100%"}} className="aFade">
      <div style={{display:"grid",gridTemplateColumns:"34px 1fr 90px 90px 90px 90px",
        gap:4,padding:"4px 12px",borderBottom:`1px solid ${C.border}`,flexShrink:0}}>
        {["","Asset","Qty","Avg Cost","Price","P&L"].map((h,i)=>(
          <div key={i} style={{fontSize:8,color:C.muted,textTransform:"uppercase",letterSpacing:".1em",
            textAlign:i>1?"right":"left"}}>{h}</div>
        ))}
      </div>
      <div style={{flex:1,overflowY:"auto"}}>
        {rows.map(a=>{
          const net=NETS.find(n=>n.id===a.net); const v=a.qty*a.price;
          return (
            <div key={a.sym} className="tx-summary" style={{display:"grid",gridTemplateColumns:"34px 1fr 90px 90px 90px 90px",
              gap:4,padding:"7px 12px",alignItems:"center",cursor:"default"}}>
              <div style={{width:26,height:26,borderRadius:5,background:C.hi,display:"flex",alignItems:"center",
                justifyContent:"center",fontSize:8,fontWeight:800,color:C.cyan,fontFamily:"'IBM Plex Mono',monospace"}}>
                {a.sym.slice(0,2)}
              </div>
              <div>
                <div style={{fontWeight:600,fontSize:12,display:"flex",alignItems:"center",gap:5}}>
                  {a.sym}
                  {a.issue==="recon"&&<span title="Balance mismatch"><Icon n="warn" s={10} c={C.amber}/></span>}
                  <span style={{fontSize:10,color:net?.color,opacity:.9}}>{net?.icon}</span>
                </div>
                <div style={{fontSize:9,color:C.sub}}>{fmt$(v)}</div>
              </div>
              <div className="mono" style={{fontSize:10,color:C.sub,textAlign:"right"}}>
                {a.qty>=1000?a.qty.toLocaleString():a.qty}
              </div>
              <div className="mono" style={{fontSize:10,color:C.sub,textAlign:"right"}}>{fmt$(a.avco)}</div>
              <div className="mono" style={{fontSize:10,color:C.text,textAlign:"right"}}>{fmt$(a.price)}</div>
              <div style={{textAlign:"right"}}>
                <div className="mono" style={{fontSize:11,fontWeight:600,color:a.uPnl>=0?C.green:C.red}}>{fmtPct(a.uPnl)}</div>
                <div className="mono" style={{fontSize:9,color:C.sub}}>{fmt$(a.uPnlUsd)}</div>
              </div>
            </div>
          );
        })}
      </div>
      <div style={{borderTop:`1px solid ${C.border}`,padding:"5px 12px",display:"flex",justifyContent:"space-between",flexShrink:0}}>
        <span className="mono" style={{fontSize:9,color:C.muted}}>{rows.length} assets</span>
        <span className="mono" style={{fontSize:9,color:C.sub}}>{fmt$(rows.reduce((s,a)=>s+a.qty*a.price,0))} total</span>
      </div>
    </div>
  );
};

/* ═══ SECTION: LP ═══════════════════════════════════════════ */
const SLP = ({fW,fN}) => {
  const [sub,setSub]=useState("all");
  const rows=LP_POSITIONS.filter(l=>{
    if(sub==="open"&&l.status!=="open")return false;
    if(sub==="closed"&&l.status!=="closed")return false;
    if(fW.length&&!fW.includes(l.w))return false;
    if(fN.length&&!fN.includes(l.net))return false;
    return true;
  });
  return (
    <div style={{display:"flex",flexDirection:"column",height:"100%"}} className="aFade">
      <div style={{padding:"6px 12px",borderBottom:`1px solid ${C.border}`,display:"flex",gap:6,alignItems:"center",flexShrink:0}}>
        {["all","open","closed"].map(f=>(
          <button key={f} onClick={()=>setSub(f)} style={{
            padding:"3px 10px",borderRadius:12,fontSize:10,fontWeight:600,
            background:sub===f?C.cyanDim:"transparent",
            border:`1px solid ${sub===f?C.cyanMid:C.border}`,
            color:sub===f?C.cyan:C.sub,
          }}>{f[0].toUpperCase()+f.slice(1)}</button>
        ))}
        <span style={{flex:1}}/>
        <span style={{fontSize:10,color:C.sub}}>Total fees: <span className="mono" style={{color:C.green}}>
          {fmt$(LP_POSITIONS.filter(l=>l.status==="open").reduce((s,l)=>s+l.fees,0))}
        </span></span>
      </div>
      <div style={{flex:1,overflowY:"auto",padding:"10px 12px",display:"flex",flexDirection:"column",gap:8}}>
        {rows.map(l=>{
          const net=NETS.find(n=>n.id===l.net);
          return (
            <div key={l.id} style={{background:C.surface,
              border:`1px solid ${l.status==="open"?C.greenMid:C.border}`,borderRadius:8,padding:"11px 13px"}}>
              <div style={{display:"flex",justifyContent:"space-between",alignItems:"flex-start",marginBottom:9}}>
                <div>
                  <div style={{fontWeight:700,fontSize:13,display:"flex",alignItems:"center",gap:7}}>
                    {l.pair}
                    <Pill v={l.status==="open"?"green":"def"}>{l.status}</Pill>
                    {l.nftId&&<span className="mono" style={{fontSize:8,color:C.muted}}>{l.nftId}</span>}
                  </div>
                  <div style={{fontSize:10,color:C.sub,marginTop:2,display:"flex",gap:5,alignItems:"center"}}>
                    {l.protocol}
                    <span style={{color:net?.color}}>{net?.icon}</span>
                    {l.net}
                    {l.range&&<span style={{color:C.muted}}>· {l.range}</span>}
                  </div>
                </div>
                <div style={{textAlign:"right"}}>
                  <div className="mono" style={{fontSize:16,fontWeight:700,color:l.status==="open"?C.text:C.sub}}>{fmt$(l.value)}</div>
                  <div style={{fontSize:9,color:C.muted}}>current value</div>
                </div>
              </div>
              <div style={{display:"grid",gridTemplateColumns:"repeat(4,1fr)",gap:6}}>
                {[
                  {l:"Fees earned",   v:fmt$(l.fees),     c:C.green},
                  {l:"Imp. loss",     v:fmtPct(l.il),     c:l.il>=0?C.green:C.red, sub:fmt$(l.ilUsd)},
                  {l:"Total P&L",     v:fmtPct(l.pnl),    c:l.pnl>=0?C.green:C.red, sub:fmt$(l.pnlUsd)},
                  {l:"Entered",       v:l.entered,         c:C.sub, mono:true},
                ].map(m=>(
                  <div key={m.l} style={{background:C.hi,borderRadius:5,padding:"6px 8px"}}>
                    <div style={{fontSize:8,color:C.muted,textTransform:"uppercase",letterSpacing:".08em",marginBottom:2}}>{m.l}</div>
                    <div className={m.mono?"mono":""} style={{fontSize:m.mono?9:12,fontWeight:700,color:m.c}}>{m.v}</div>
                    {m.sub&&<div className="mono" style={{fontSize:9,color:C.sub}}>{m.sub}</div>}
                  </div>
                ))}
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
};

/* ═══ SECTION: LENDING ══════════════════════════════════════ */
const SLending = ({fW,fN}) => {
  const rows=LENDING.filter(l=>{
    if(fW.length&&!fW.includes(l.w))return false;
    if(fN.length&&!fN.includes(l.net))return false;
    return true;
  });
  const totalDep=rows.filter(l=>l.type==="deposit").reduce((s,l)=>s+l.value,0);
  const totalBor=rows.filter(l=>l.type==="borrow").reduce((s,l)=>s+l.value,0);
  const hf=(totalDep/Math.max(totalBor,1)*0.75).toFixed(2);
  const hfC=parseFloat(hf)>2?C.green:parseFloat(hf)>1.2?C.amber:C.red;
  return (
    <div style={{display:"flex",flexDirection:"column",height:"100%"}} className="aFade">
      {/* Health */}
      <div style={{padding:"8px 12px",borderBottom:`1px solid ${C.border}`,
        display:"flex",gap:14,alignItems:"center",flexShrink:0}}>
        <div style={{display:"flex",alignItems:"baseline",gap:6}}>
          <span style={{fontSize:10,color:C.sub}}>Health factor</span>
          <span className="mono" style={{fontSize:16,fontWeight:700,color:hfC}}>{hf}</span>
        </div>
        <div style={{flex:1,maxWidth:140}}><PBar v={parseFloat(hf)/3*100} c={hfC} h={4}/></div>
        <span style={{fontSize:10,color:C.sub}}>
          Supply <span className="mono" style={{color:C.green}}>{fmt$(totalDep)}</span>
          {" · "}
          Borrow <span className="mono" style={{color:C.red}}>{fmt$(totalBor)}</span>
        </span>
      </div>
      <div style={{flex:1,overflowY:"auto",padding:"10px 12px",display:"flex",flexDirection:"column",gap:10}}>
        {/* Deposits */}
        <SL>Supply positions</SL>
        <div style={{display:"flex",flexDirection:"column",gap:5}}>
          {rows.filter(l=>l.type==="deposit").map(l=>{
            const net=NETS.find(n=>n.id===l.net);
            return (
              <div key={l.id} style={{display:"grid",gridTemplateColumns:"32px 1fr 100px 80px 70px 80px",
                gap:8,padding:"8px 10px",background:C.surface,border:`1px solid ${C.greenMid}22`,borderRadius:7,alignItems:"center"}}>
                <div style={{width:28,height:28,borderRadius:6,background:C.greenDim,border:`1px solid ${C.greenMid}`,
                  display:"flex",alignItems:"center",justifyContent:"center"}}>
                  <Icon n="arr_u" s={12} c={C.green}/>
                </div>
                <div>
                  <div style={{fontWeight:600,fontSize:12}}>{l.asset} <span style={{fontSize:9,color:C.sub}}>· {l.protocol}</span></div>
                  <div style={{fontSize:9,color:C.sub,display:"flex",gap:4}}><span style={{color:net?.color}}>{net?.icon}</span>{l.net}</div>
                </div>
                <div className="mono" style={{fontSize:11,textAlign:"right"}}>{l.qty} {l.asset}</div>
                <div className="mono" style={{fontSize:11,textAlign:"right"}}>{fmt$(l.value)}</div>
                <div className="mono" style={{fontSize:11,color:C.green,textAlign:"right"}}>{l.apy}%</div>
                <div className="mono" style={{fontSize:11,color:C.green,textAlign:"right"}}>+{fmt$(l.earned)}</div>
              </div>
            );
          })}
        </div>
        <Divider/>
        <SL>Borrow positions</SL>
        <div style={{display:"flex",flexDirection:"column",gap:5}}>
          {rows.filter(l=>l.type==="borrow").map(l=>{
            const net=NETS.find(n=>n.id===l.net);
            return (
              <div key={l.id} style={{display:"grid",gridTemplateColumns:"32px 1fr 100px 80px 70px 80px",
                gap:8,padding:"8px 10px",background:C.surface,border:`1px solid ${C.redDim}`,borderRadius:7,alignItems:"center"}}>
                <div style={{width:28,height:28,borderRadius:6,background:C.redDim,border:`1px solid ${C.red}33`,
                  display:"flex",alignItems:"center",justifyContent:"center"}}>
                  <Icon n="arr_d" s={12} c={C.red}/>
                </div>
                <div>
                  <div style={{fontWeight:600,fontSize:12}}>{l.asset} <span style={{fontSize:9,color:C.sub}}>· {l.protocol}</span></div>
                  <div style={{fontSize:9,color:C.sub,display:"flex",gap:4}}><span style={{color:net?.color}}>{net?.icon}</span>{l.net}</div>
                </div>
                <div className="mono" style={{fontSize:11,textAlign:"right"}}>{l.qty} {l.asset}</div>
                <div className="mono" style={{fontSize:11,textAlign:"right"}}>{fmt$(l.value)}</div>
                <div className="mono" style={{fontSize:11,color:C.red,textAlign:"right"}}>{l.apy}%</div>
                <div className="mono" style={{fontSize:11,color:C.red,textAlign:"right"}}>{fmt$(l.interest)}</div>
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
};

/* ═══ SIDEBAR FILTERS ═══════════════════════════════════════ */
const Sidebar = ({section,fW,fN,hide,togW,togN,setHide}) => {
  const [collapsed,setCollapsed]=useState(false);
  const cnt=fW.length+fN.length;
  return (
    <div style={{width:collapsed?36:182,transition:"width .18s ease",borderRight:`1px solid ${C.border}`,
      flexShrink:0,display:"flex",flexDirection:"column",overflow:"hidden"}}>
      {/* Toggle */}
      <button onClick={()=>setCollapsed(c=>!c)} style={{
        display:"flex",alignItems:"center",justifyContent:collapsed?"center":"space-between",
        gap:5,padding:"8px 10px",color:C.sub,fontSize:10,fontWeight:700,
        borderBottom:`1px solid ${C.border}`,letterSpacing:".08em",textTransform:"uppercase",
      }}>
        {!collapsed&&<span>Filters</span>}
        {!collapsed&&cnt>0&&(
          <span style={{background:C.cyan,color:"#000",borderRadius:"50%",width:14,height:14,
            display:"flex",alignItems:"center",justifyContent:"center",fontSize:8,fontWeight:800}}>{cnt}</span>
        )}
        <Ic d={<polyline points="9 18 15 12 9 6"/>} s={11} c={C.sub}
          style={{transform:collapsed?"rotate(180deg)":"none",transition:"transform .18s"}}/>
      </button>

      {!collapsed&&(
        <div style={{flex:1,overflowY:"auto",padding:"10px 9px",display:"flex",flexDirection:"column",gap:11}}>
          {/* Wallets */}
          <div>
            <SL>Wallet</SL>
            {WALLETS.map(w=>(
              <button key={w.id} onClick={()=>togW(w.id)} style={{
                width:"100%",display:"flex",alignItems:"center",gap:6,padding:"5px 6px",borderRadius:5,
                background:fW.includes(w.id)?w.color+"15":"transparent",
                border:`1px solid ${fW.includes(w.id)?w.color+"44":"transparent"}`,
                color:fW.includes(w.id)?w.color:C.sub,
                fontSize:11,fontWeight:600,textAlign:"left",marginBottom:2,
              }}>
                <div style={{width:12,height:12,borderRadius:3,flexShrink:0,
                  border:`2px solid ${fW.includes(w.id)?w.color:C.border}`,
                  background:fW.includes(w.id)?w.color:"transparent",
                  display:"flex",alignItems:"center",justifyContent:"center"}}>
                  {fW.includes(w.id)&&<Ic d={<polyline points="20 6 9 17 4 12"/>} s={7} c="#000" sw={3}/>}
                </div>
                <div style={{width:6,height:6,borderRadius:"50%",background:w.color,flexShrink:0}}/>
                <div style={{overflow:"hidden",flex:1,minWidth:0}}>
                  <div style={{overflow:"hidden",textOverflow:"ellipsis",whiteSpace:"nowrap"}}>{w.label}</div>
                  <div className="mono" style={{fontSize:8,color:C.muted}}>{short(w.addr)}</div>
                </div>
              </button>
            ))}
          </div>
          <Divider/>
          {/* Networks */}
          <div>
            <SL>Network</SL>
            {NETS.map(n=>(
              <button key={n.id} onClick={()=>togN(n.id)} style={{
                width:"100%",display:"flex",alignItems:"center",gap:6,padding:"4px 6px",borderRadius:5,
                background:fN.includes(n.id)?n.color+"12":"transparent",
                border:`1px solid ${fN.includes(n.id)?n.color+"44":"transparent"}`,
                color:fN.includes(n.id)?n.color:C.sub,
                fontSize:11,fontWeight:600,textAlign:"left",marginBottom:2,
              }}>
                <div style={{width:12,height:12,borderRadius:3,flexShrink:0,
                  border:`2px solid ${fN.includes(n.id)?n.color:C.border}`,
                  background:fN.includes(n.id)?n.color:"transparent",
                  display:"flex",alignItems:"center",justifyContent:"center"}}>
                  {fN.includes(n.id)&&<Ic d={<polyline points="20 6 9 17 4 12"/>} s={7} c="#000" sw={3}/>}
                </div>
                <span style={{fontSize:13}}>{n.icon}</span>
                <span>{n.label}</span>
              </button>
            ))}
          </div>
          <Divider/>
          {/* Context-specific */}
          {section==="tokens"&&(
            <label style={{display:"flex",alignItems:"center",gap:6,cursor:"pointer",userSelect:"none"}}>
              <span className="toggle"><input type="checkbox" checked={hide} onChange={e=>setHide(e.target.checked)}/><span className="tslider"/></span>
              <span style={{fontSize:11,color:C.sub}}>Hide &lt;$0.50</span>
            </label>
          )}
          {section==="lending"&&(
            <div style={{padding:"7px 8px",background:C.amberDim,border:`1px solid ${C.amberMid}`,borderRadius:5}}>
              <div style={{fontSize:10,color:C.amber,fontWeight:600,marginBottom:1}}>⚠ Liq. risk</div>
              <div style={{fontSize:9,color:C.sub,lineHeight:1.4}}>Health factor from last on-chain sync</div>
            </div>
          )}
          {cnt>0&&(
            <button onClick={()=>{togW("_clear");togN("_clear");}} style={{
              background:"none",border:`1px solid ${C.border}`,color:C.sub,
              borderRadius:4,padding:"4px 7px",fontSize:10,width:"100%",
              fontFamily:"'DM Sans',sans-serif",
            }}>Clear all</button>
          )}
        </div>
      )}
    </div>
  );
};

/* ═══ ICON NAV ══════════════════════════════════════════════ */
const SECTIONS = [
  {id:"tokens", icon:"tokens", label:"Tokens",  color:C.cyan},
  {id:"lp",     icon:"lp",     label:"LP",       color:C.blue},
  {id:"lending",icon:"lending",label:"Lending",  color:C.green},
  {id:"staking",icon:"staking",label:"Staking",  color:C.amber, soon:true},
];

const IconNav = ({section,setSection}) => (
  <div style={{width:44,borderRight:`1px solid ${C.border}`,display:"flex",
    flexDirection:"column",alignItems:"center",padding:"6px 0",gap:2,flexShrink:0}}>
    {SECTIONS.map(s=>(
      <button key={s.id} onClick={()=>!s.soon&&setSection(s.id)}
        title={s.label+(s.soon?" (soon)":"")}
        style={{
          width:36,height:36,borderRadius:7,display:"flex",alignItems:"center",justifyContent:"center",
          background:section===s.id?s.color+"20":"transparent",
          border:`1px solid ${section===s.id?s.color+"44":"transparent"}`,
          color:section===s.id?s.color:s.soon?C.muted:C.sub,
          opacity:s.soon?.45:1,
          transition:"all .12s",position:"relative",cursor:s.soon?"not-allowed":"pointer",
        }}>
        <Ic d={DEFS[s.icon]} s={15} c={section===s.id?s.color:s.soon?C.muted:C.sub}/>
        {section===s.id&&(
          <div style={{position:"absolute",left:0,top:"20%",width:2,height:"60%",
            background:s.color,borderRadius:"0 2px 2px 0"}}/>
        )}
        {s.soon&&(
          <div style={{position:"absolute",top:4,right:4,width:5,height:5,borderRadius:"50%",
            background:C.amber,opacity:.6}}/>
        )}
      </button>
    ))}
  </div>
);

/* ═══ MAIN ══════════════════════════════════════════════════ */
export default function App() {
  const [section,  setSection] = useState("tokens");
  const [fW, setFW] = useState([]);
  const [fN, setFN] = useState([]);
  const [hide, setHide] = useState(false);
  const togW = id=>{ if(id==="_clear"){setFW([]);return;} setFW(p=>p.includes(id)?p.filter(x=>x!==id):[...p,id]); };
  const togN = id=>{ if(id==="_clear"){setFN([]);return;} setFN(p=>p.includes(id)?p.filter(x=>x!==id):[...p,id]); };
  const sec  = SECTIONS.find(s=>s.id===section);

  return (
    <div style={{height:"100vh",display:"flex",flexDirection:"column",background:C.bg,overflow:"hidden"}}>
      <style>{G}</style>

      {/* ── TOPBAR ── */}
      <div style={{flexShrink:0,borderBottom:`1px solid ${C.border}`,background:C.surface+"f0",
        backdropFilter:"blur(12px)",zIndex:50}}>
        <div style={{display:"flex",alignItems:"center",gap:14,padding:"0 14px",height:46}}>
          {/* Logo */}
          <div style={{display:"flex",alignItems:"center",gap:7,flexShrink:0}}>
            <div style={{width:24,height:24,background:C.cyan,borderRadius:5,display:"flex",alignItems:"center",justifyContent:"center",fontSize:12}}>⚡</div>
            <span style={{fontWeight:800,fontSize:13,letterSpacing:"-.02em"}}>WalletRadar</span>
          </div>
          {/* Metrics */}
          <div style={{display:"flex",gap:16}}>
            {[{l:"Portfolio",v:"$47,284",c:C.text},{l:"Unrealised",v:"+7.4%",c:C.green},{l:"Realised",v:"+$1,880",c:C.green}]
              .map(m=>(
                <div key={m.l} style={{display:"flex",gap:5,alignItems:"baseline"}}>
                  <span style={{fontSize:9,color:C.sub}}>{m.l}</span>
                  <span className="mono" style={{fontSize:13,fontWeight:700,color:m.c}}>{m.v}</span>
                </div>
              ))}
          </div>
          <div style={{flex:1}}/>
          {/* Backfill */}
          <div style={{display:"flex",alignItems:"center",gap:7,padding:"3px 10px",
            background:C.cyanDim,border:`1px solid ${C.cyanMid}`,borderRadius:5}}>
            <Spin s={11}/>
            <span style={{fontSize:10,color:C.cyan,fontWeight:600}}>Backfill 68%</span>
            <div style={{width:56}}><PBar v={68}/></div>
            <span style={{fontSize:9,color:C.sub}}>ARB · BASE · OP</span>
          </div>
          {/* Wallets */}
          {WALLETS.map(w=>(
            <div key={w.id} style={{display:"flex",alignItems:"center",gap:4,padding:"3px 8px",
              background:C.hi,border:`1px solid ${C.border}`,borderRadius:5}}>
              <div style={{width:6,height:6,borderRadius:"50%",background:w.color}}/>
              <span className="mono" style={{fontSize:9,color:C.sub}}>{short(w.addr)}</span>
              <span style={{fontSize:9,color:C.sub}}>· {w.label}</span>
            </div>
          ))}
          <button style={{display:"flex",alignItems:"center",gap:5,padding:"5px 11px",
            background:C.cyan,color:"#000",borderRadius:5,fontWeight:700,fontSize:11}}>
            <Ic d={DEFS.wallet} s={11} c="#000"/> Add wallet
          </button>
        </div>
      </div>

      {/* ── BODY ── */}
      <div style={{flex:1,display:"flex",overflow:"hidden"}}>
        <IconNav section={section} setSection={setSection}/>
        <Sidebar section={section} fW={fW} fN={fN} hide={hide} togW={togW} togN={togN} setHide={setHide}/>

        {/* Content area */}
        <div style={{flex:1,display:"flex",flexDirection:"column",overflow:"hidden"}}>
          {/* Section breadcrumb */}
          <div style={{padding:"5px 12px",borderBottom:`1px solid ${C.border}`,
            display:"flex",alignItems:"center",gap:7,flexShrink:0}}>
            <Ic d={DEFS[sec.icon]} s={11} c={sec.color}/>
            <span style={{fontWeight:700,fontSize:11,color:sec.color}}>{sec.label}</span>
            {(fW.length+fN.length)>0&&<>
              <span style={{color:C.muted,fontSize:10}}>·</span>
              {fW.map(id=>{const w=WALLETS.find(x=>x.id===id);return(
                <span key={id} style={{display:"flex",alignItems:"center",gap:3,padding:"1px 6px",
                  borderRadius:10,background:w.color+"18",border:`1px solid ${w.color}33`,fontSize:9,color:w.color}}>
                  <div style={{width:4,height:4,borderRadius:"50%",background:w.color}}/>{w.label}
                </span>
              );})}
              {fN.map(id=>{const n=NETS.find(x=>x.id===id);return(
                <span key={id} style={{display:"flex",alignItems:"center",gap:3,padding:"1px 6px",
                  borderRadius:10,background:n.color+"15",border:`1px solid ${n.color}33`,fontSize:9,color:n.color}}>
                  {n.icon} {n.label}
                </span>
              );})}
            </>}
          </div>

          {/* Two-column: section content + transactions */}
          <div style={{flex:1,display:"flex",overflow:"hidden"}}>
            <div style={{flex:"0 0 48%",overflow:"hidden",display:"flex",flexDirection:"column"}}>
              {section==="tokens"  &&<STokens  fW={fW} fN={fN} hide={hide}/>}
              {section==="lp"      &&<SLP      fW={fW} fN={fN}/>}
              {section==="lending" &&<SLending fW={fW} fN={fN}/>}
            </div>
            <div style={{flex:1,overflow:"hidden",display:"flex",flexDirection:"column"}}>
              <TxPanel fW={fW} fN={fN}/>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
