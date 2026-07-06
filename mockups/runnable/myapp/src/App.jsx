import { useState, useRef, useEffect } from "react";

/* ═══ TOKENS ═══════════════════════════════════════════════ */
const C = {
  bg:"#07090f", s1:"#0b0d18", s2:"#0f1220", s3:"#131728", hov:"#121628",
  border:"#181d30", borderHi:"#222840",
  cyan:"#22d3ee", cyanDim:"#22d3ee12", cyanMid:"#22d3ee32",
  green:"#34d399", greenDim:"#34d39910", greenMid:"#34d39930",
  red:"#f87171",  redDim:"#f8717110",  redMid:"#f8717132",
  amber:"#fbbf24", amberDim:"#fbbf2410", amberMid:"#fbbf2432",
  purple:"#a78bfa", purpleDim:"#a78bfa10", purpleMid:"#a78bfa30",
  blue:"#60a5fa",  blueDim:"#60a5fa10",   blueMid:"#60a5fa30",
  text:"#dde3f0", sub:"#4a5878", muted:"#1e2538",
};
const COLORS = [C.cyan, C.purple, C.green, C.amber, C.blue, "#f472b6"];

const G = `
@import url('https://fonts.googleapis.com/css2?family=IBM+Plex+Mono:wght@400;500;600&family=DM+Sans:wght@400;500;600;700&display=swap');
*{box-sizing:border-box;margin:0;padding:0}
html,body,#root{height:100%;overflow:hidden}
body{background:${C.bg};color:${C.text};font-family:'DM Sans',sans-serif;font-size:13px}
.mono{font-family:'IBM Plex Mono',monospace}
::-webkit-scrollbar{width:3px;height:3px}::-webkit-scrollbar-track{background:transparent}
::-webkit-scrollbar-thumb{background:${C.borderHi};border-radius:2px}
button,input,select{font-family:'DM Sans',sans-serif;border:none;background:none;color:${C.text};outline:none}
button{cursor:pointer}
@keyframes fadeIn{from{opacity:0;transform:translateY(5px)}to{opacity:1;transform:translateY(0)}}
@keyframes slideIn{from{opacity:0;transform:translateX(20px)}to{opacity:1;transform:translateX(0)}}
@keyframes pulse{0%,100%{opacity:1}50%{opacity:.3}}
@keyframes spin{to{transform:rotate(360deg)}}
.aFade{animation:fadeIn .2s ease both}
.aSlide{animation:slideIn .22s cubic-bezier(.16,1,.3,1) both}
`;

/* ═══ ICONS ════════════════════════════════════════════════ */
const Icon = ({n,s=13,c="currentColor"}) => {
  const d = {
    lending:  <><path d="M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/><polyline points="9 22 9 12 15 12 15 22"/></>,
    lp:       <><path d="M3 3l7.07 16.97 2.51-7.39 7.39-2.51L3 3z"/><path d="M13 13l6 6"/></>,
    tokens:   <><circle cx="12" cy="12" r="9"/><path d="M9 12h6M12 9v6"/></>,
    staking:  <><polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/></>,
    gear:     <><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06A1.65 1.65 0 0 0 4.68 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06A1.65 1.65 0 0 0 9 4.68a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"/></>,
    chev_d:   <polyline points="6 9 12 15 18 9"/>,
    chev_r:   <polyline points="9 18 15 12 9 6"/>,
    chev_l:   <polyline points="15 18 9 12 15 6"/>,
    x:        <><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></>,
    warn:     <><path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/><line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/></>,
    info:     <><circle cx="12" cy="12" r="10"/><line x1="12" y1="16" x2="12" y2="12"/><line x1="12" y1="8" x2="12.01" y2="8"/></>,
    arr_up:   <><line x1="12" y1="19" x2="12" y2="5"/><polyline points="5 12 12 5 19 12"/></>,
    arr_dn:   <><line x1="12" y1="5" x2="12" y2="19"/><polyline points="19 12 12 19 5 12"/></>,
    link:     <><path d="M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71"/><path d="M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71"/></>,
    loop:     <><polyline points="17 1 21 5 17 9"/><path d="M3 11V9a4 4 0 0 1 4-4h14"/><polyline points="7 23 3 19 7 15"/><path d="M21 13v2a4 4 0 0 1-4 4H3"/></>,
    fee:      <><line x1="12" y1="1" x2="12" y2="23"/><path d="M17 5H9.5a3.5 3.5 0 0 0 0 7h5a3.5 3.5 0 0 1 0 7H6"/></>,
    range:    <><line x1="8" y1="6" x2="8" y2="18"/><line x1="16" y1="6" x2="16" y2="18"/><line x1="8" y1="12" x2="16" y2="12"/></>,
    filter:   <><polygon points="22 3 2 3 10 12.46 10 19 14 21 14 12.46 22 3"/></>,
    health:   <><path d="M22 12h-4l-3 9L9 3l-3 9H2"/></>,
    wallet:   <><rect x="2" y="7" width="20" height="14" rx="2"/><path d="M16 3l-4 4-4-4"/></>,
    external: <><path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"/><polyline points="15 3 21 3 21 9"/><line x1="10" y1="14" x2="21" y2="3"/></>,
  };
  return (
    <svg width={s} height={s} viewBox="0 0 24 24" fill="none" stroke={c}
      strokeWidth={1.7} strokeLinecap="round" strokeLinejoin="round" style={{flexShrink:0}}>
      {d[n]}
    </svg>
  );
};

/* ═══ PRIMITIVES ════════════════════════════════════════════ */
const Tag = ({children,v="def"}) => {
  const m={def:{bg:C.s2,c:C.sub,b:C.border},cyan:{bg:C.cyanDim,c:C.cyan,b:C.cyanMid},
    green:{bg:C.greenDim,c:C.green,b:C.greenMid},amber:{bg:C.amberDim,c:C.amber,b:C.amberMid},
    red:{bg:C.redDim,c:C.red,b:C.redMid},purple:{bg:C.purpleDim,c:C.purple,b:C.purpleMid},
    blue:{bg:C.blueDim,c:C.blue,b:C.blueMid}};
  const s=m[v]||m.def;
  return <span style={{display:"inline-flex",alignItems:"center",gap:3,padding:"1px 6px",borderRadius:3,
    fontSize:9,fontWeight:700,fontFamily:"'IBM Plex Mono',monospace",letterSpacing:".04em",
    background:s.bg,color:s.c,border:`1px solid ${s.b}`}}>{children}</span>;
};

const Divider = ({my=8}) => <div style={{borderTop:`1px solid ${C.border}`,margin:`${my}px 0`}}/>;

const fmt$ = v => {
  const abs=Math.abs(v);
  const str=abs>=1000000?`$${(abs/1000000).toFixed(2)}M`:abs>=1000?`$${(abs/1000).toFixed(1)}k`:`$${abs.toFixed(2)}`;
  return (v<0?"-":"")+str;
};
const fmtPct = v => `${v>=0?"+":""}${v.toFixed(2)}%`;

const Stat = ({label,value,sub,color,mono=true}) => (
  <div>
    <div style={{fontSize:8,fontWeight:700,color:C.muted,textTransform:"uppercase",letterSpacing:".1em",marginBottom:3}}>{label}</div>
    <div className={mono?"mono":""} style={{fontSize:14,fontWeight:700,color:color||C.text,lineHeight:1.2}}>{value}</div>
    {sub&&<div style={{fontSize:9,color:C.sub,marginTop:2}}>{sub}</div>}
  </div>
);

/* ═══ HEALTH FACTOR ════════════════════════════════════════ */
const HealthBar = ({hf,compact=false}) => {
  const hfN = parseFloat(hf);
  const color = hfN >= 2 ? C.green : hfN >= 1.2 ? C.amber : C.red;
  const label = hfN >= 2 ? "Safe" : hfN >= 1.5 ? "Moderate" : hfN >= 1.1 ? "At risk" : "Liquidation risk";
  const pct   = Math.min(hfN / 3 * 100, 100);

  if (compact) return (
    <div style={{display:"flex",alignItems:"center",gap:7}}>
      <div style={{flex:1,height:4,background:C.border,borderRadius:2,overflow:"hidden",minWidth:60}}>
        <div style={{width:`${pct}%`,height:"100%",background:color,borderRadius:2,transition:"width .4s"}}/>
      </div>
      <span className="mono" style={{fontSize:11,fontWeight:700,color,flexShrink:0}}>{hfN.toFixed(2)}</span>
    </div>
  );

  return (
    <div style={{padding:"10px 13px",background:color+"0a",border:`1px solid ${color}25`,borderRadius:7}}>
      <div style={{display:"flex",justifyContent:"space-between",alignItems:"baseline",marginBottom:6}}>
        <div style={{fontSize:10,fontWeight:700,color,textTransform:"uppercase",letterSpacing:".07em"}}>
          Health factor
        </div>
        <div className="mono" style={{fontSize:20,fontWeight:800,color}}>{hfN.toFixed(2)}</div>
      </div>
      {/* Gradient bar */}
      <div style={{position:"relative",height:8,borderRadius:4,overflow:"hidden",marginBottom:5,
        background:`linear-gradient(to right, ${C.red}, ${C.amber} 40%, ${C.green})`}}>
        {/* Needle */}
        <div style={{position:"absolute",top:"-2px",width:3,height:12,background:"#fff",borderRadius:2,
          left:`calc(${pct}% - 1.5px)`,boxShadow:"0 0 6px rgba(0,0,0,.5)",transition:"left .4s"}}/>
      </div>
      <div style={{display:"flex",justifyContent:"space-between",fontSize:8,color:C.muted}}>
        <span>1.0 liquidation</span><span>2.0 safe</span><span>3.0+</span>
      </div>
      <div style={{marginTop:6,fontSize:10,color,fontWeight:600}}>{label}</div>
    </div>
  );
};

/* ═══ NETS ══════════════════════════════════════════════════ */
const NETS = {
  ETH: {icon:"⟠",label:"Ethereum",  color:"#627EEA"},
  ARB: {icon:"△",label:"Arbitrum",  color:"#28A0F0"},
  BASE:{icon:"◆",label:"Base",      color:"#0052FF"},
  OP:  {icon:"○",label:"Optimism",  color:"#FF0420"},
  POL: {icon:"⬡",label:"Polygon",   color:"#7B3FE4"},
};
const NetBadge = ({net,s=11}) => {
  const n=NETS[net]||{icon:"◎",label:net,color:C.sub};
  return <span style={{fontSize:s,color:n.color,display:"inline-flex",alignItems:"center",gap:3}}>
    {n.icon}<span style={{fontSize:s-1,fontWeight:600}}>{n.label}</span>
  </span>;
};

/* ═══════════════════════════════════════════════════════════
   LENDING DATA
═══════════════════════════════════════════════════════════ */
const LENDING_PROTOCOLS = [
  {
    id:"aave-eth", protocol:"Aave V3", net:"ETH", version:"v3",
    healthFactor:"1.84",
    supplyUsd:12480, borrowUsd:4320,
    netExposureUsd:8160,
    collateralRatio:0.653,
    positions:[
      {type:"supply", asset:"ETH",  qty:2.5,   price:3100, usd:7750, apy:2.8,  collateral:true},
      {type:"supply", asset:"USDC", qty:4730,  price:1,    usd:4730, apy:4.1,  collateral:false},
      {type:"borrow", asset:"USDC", qty:4320,  price:1,    usd:4320, apy:-5.2, collateral:false},
    ],
    history:[
      {id:"h1",date:"2025-01-08",type:"LEND_DEPOSIT",asset:"ETH",  qty:"+1.0", usd:"+$3,100",hash:"0xabc…1234",net:"ETH"},
      {id:"h2",date:"2025-01-08",type:"BORROW",       asset:"USDC",qty:"+2000",usd:"+$2,000",hash:"0xabc…1235",net:"ETH",loopId:"lp1"},
      {id:"h3",date:"2025-01-08",type:"LEND_DEPOSIT",asset:"ETH",  qty:"+0.64",usd:"+$1,984",hash:"0xabc…1236",net:"ETH",loopId:"lp1"},
      {id:"h4",date:"2024-12-01",type:"LEND_DEPOSIT",asset:"USDC", qty:"+4730",usd:"+$4,730",hash:"0xabc…1100",net:"ETH"},
      {id:"h5",date:"2024-11-14",type:"BORROW",       asset:"USDC",qty:"+2320",usd:"+$2,320",hash:"0xabc…1088",net:"ETH"},
      {id:"h6",date:"2024-11-14",type:"REPAY",         asset:"USDC",qty:"-320", usd:"-$320",  hash:"0xabc…1089",net:"ETH"},
      {id:"h7",date:"2024-10-20",type:"LEND_DEPOSIT",asset:"ETH",  qty:"+1.5", usd:"+$4,050",hash:"0xabc…1001",net:"ETH"},
    ],
  },
  {
    id:"aave-op", protocol:"Aave V3", net:"OP", version:"v3",
    healthFactor:"2.41",
    supplyUsd:3100, borrowUsd:800,
    netExposureUsd:2300,
    collateralRatio:0.742,
    positions:[
      {type:"supply", asset:"WETH", qty:1.0,  price:3100, usd:3100, apy:3.1, collateral:true},
      {type:"borrow", asset:"DAI",  qty:800,  price:1,    usd:800,  apy:-4.5,collateral:false},
    ],
    history:[
      {id:"h8",date:"2024-12-10",type:"LEND_DEPOSIT",asset:"WETH",qty:"+1.0",usd:"+$3,100",hash:"0xdef…5501",net:"OP"},
      {id:"h9",date:"2024-12-10",type:"BORROW",       asset:"DAI", qty:"+800",usd:"+$800",  hash:"0xdef…5502",net:"OP"},
    ],
  },
  {
    id:"compound-eth", protocol:"Compound V3", net:"ETH", version:"v3",
    healthFactor:"3.20",
    supplyUsd:2496, borrowUsd:0,
    netExposureUsd:2496,
    collateralRatio:1,
    positions:[
      {type:"supply", asset:"WBTC", qty:0.04, price:62400, usd:2496, apy:1.8, collateral:true},
    ],
    history:[
      {id:"h10",date:"2024-11-01",type:"LEND_DEPOSIT",asset:"WBTC",qty:"+0.04",usd:"+$2,496",hash:"0xfff…2201",net:"ETH"},
    ],
  },
  {
    id:"aave-arb-closed", protocol:"Aave V3", net:"ARB", version:"v3",
    closed:true,
    healthFactor:null,
    supplyUsd:0, borrowUsd:0, netExposureUsd:0,
    positions:[],
    history:[
      {id:"h11",date:"2024-09-15",type:"LEND_DEPOSIT",asset:"ETH",  qty:"+0.8",usd:"+$2,200",hash:"0xaaa…9901",net:"ARB"},
      {id:"h12",date:"2024-09-15",type:"BORROW",       asset:"USDC",qty:"+1200",usd:"+$1,200",hash:"0xaaa…9902",net:"ARB"},
      {id:"h13",date:"2024-10-01",type:"REPAY",         asset:"USDC",qty:"-1218",usd:"-$1,218",hash:"0xaaa…9903",net:"ARB"},
      {id:"h14",date:"2024-10-01",type:"LEND_WITHDRAW", asset:"ETH", qty:"-0.8", usd:"-$2,200",hash:"0xaaa…9904",net:"ARB"},
    ],
  },
];

/* ═══════════════════════════════════════════════════════════
   LP DATA
═══════════════════════════════════════════════════════════ */
const LP_POSITIONS = [
  {
    id:"lp1", protocol:"Uniswap V3", pair:"ETH/USDC", net:"ETH",
    fee:0.05, nftId:"#841022", status:"active",
    inRange:true,
    priceLow:2800, priceHigh:3600, currentPrice:3180,
    priceToken:"ETH/USDC",
    token0:{sym:"ETH",  qty:0.812, usd:2581},
    token1:{sym:"USDC", qty:2410,  usd:2410},
    totalUsd:4991,
    feesEarned:182.4, feesToken0:0.024, feesToken1:107,
    il:-2.8, ilUsd:-141,
    pnl:41.4, pnlUsd:2064, pnlNet:1923,
    entered:"2024-10-12",
    utilization:0.81,
    txns:[
      {date:"2024-10-12",type:"LP_ENTRY",      qty0:"-1.2 ETH",qty1:"-3360 USDC",hash:"0xabc…1111"},
      {date:"2024-11-20",type:"LP_FEE_CLAIM",  fees:"+$44.2",  hash:"0xabc…2222"},
      {date:"2024-12-15",type:"LP_FEE_CLAIM",  fees:"+$68.8",  hash:"0xabc…3333"},
      {date:"2025-01-10",type:"LP_FEE_CLAIM",  fees:"+$69.4",  hash:"0xabc…4444"},
    ],
  },
  {
    id:"lp2", protocol:"Aerodrome", pair:"WBTC/ETH", net:"BASE",
    fee:0.3, nftId:null, status:"active",
    inRange:true,
    priceLow:null, priceHigh:null, currentPrice:null,
    priceToken:"WBTC/ETH (full range)",
    token0:{sym:"WBTC",qty:0.038,usd:2371},
    token1:{sym:"ETH", qty:0.61, usd:1891},
    totalUsd:4262,
    feesEarned:96.8, feesToken0:0.0012, feesToken1:0.021,
    il:-0.9, ilUsd:-38,
    pnl:14.8, pnlUsd:630, pnlNet:592,
    entered:"2024-12-01",
    utilization:null,
    txns:[
      {date:"2024-12-01",type:"LP_ENTRY",     qty0:"-0.04 WBTC",qty1:"-0.63 ETH",hash:"0xdef…5511"},
      {date:"2024-12-28",type:"LP_FEE_CLAIM", fees:"+$38.2",    hash:"0xdef…6622"},
      {date:"2025-01-14",type:"LP_FEE_CLAIM", fees:"+$58.6",    hash:"0xdef…7733"},
    ],
  },
  {
    id:"lp3", protocol:"Uniswap V3", pair:"ARB/USDC", net:"ARB",
    fee:0.3, nftId:"#503211", status:"active",
    inRange:false,
    priceLow:0.80, priceHigh:1.40, currentPrice:0.78,
    priceToken:"ARB/USDC",
    token0:{sym:"ARB",  qty:4200, usd:3276},
    token1:{sym:"USDC", qty:0,    usd:0},
    totalUsd:3276,
    feesEarned:34.1, feesToken0:22, feesToken1:12,
    il:-8.1, ilUsd:-288,
    pnl:-4.2, pnlUsd:-154, pnlNet:-120,
    entered:"2024-06-14",
    utilization:0,
    txns:[
      {date:"2024-06-14",type:"LP_ENTRY",     qty0:"-3800 ARB",qty1:"-3420 USDC",hash:"0xaab…1100"},
      {date:"2024-08-01",type:"LP_FEE_CLAIM", fees:"+$34.1",   hash:"0xaab…2200"},
    ],
  },
  {
    id:"lp4", protocol:"Uniswap V3", pair:"ARB/USDC", net:"ARB",
    fee:0.05, nftId:"#503322", status:"closed",
    inRange:false, closedDate:"2024-10-30",
    priceLow:1.0, priceHigh:1.8, currentPrice:null,
    priceToken:"ARB/USDC",
    token0:{sym:"ARB",  qty:0, usd:0},
    token1:{sym:"USDC", qty:0, usd:0},
    totalUsd:0,
    feesEarned:71.4, feesToken0:0, feesToken1:71.4,
    il:-9.2, ilUsd:-312,
    pnl:-4.8, pnlUsd:-241, pnlNet:-169.6,
    entered:"2024-04-20",
    utilization:null,
    txns:[
      {date:"2024-04-20",type:"LP_ENTRY",       qty0:"-2000 ARB",qty1:"-2400 USDC",hash:"0xfab…0011"},
      {date:"2024-07-15",type:"LP_FEE_CLAIM",   fees:"+$71.4",   hash:"0xfab…0022"},
      {date:"2024-10-30",type:"LP_EXIT_FINAL",  qty0:"+0 ARB",   qty1:"+2159 USDC",hash:"0xfab…0033"},
    ],
  },
];

/* ═══════════════════════════════════════════════════════════
   LENDING PAGE
═══════════════════════════════════════════════════════════ */
const LEND_HISTORY_COLORS = {
  LEND_DEPOSIT:  {c:C.green,  bg:C.greenDim,  label:"Deposit",  icon:"arr_up"},
  LEND_WITHDRAW: {c:C.red,    bg:C.redDim,    label:"Withdraw", icon:"arr_dn"},
  BORROW:        {c:C.purple, bg:C.purpleDim, label:"Borrow",   icon:"arr_dn"},
  REPAY:         {c:C.blue,   bg:C.blueDim,   label:"Repay",    icon:"arr_up"},
};

/* Loop chain detector — group items by loopId */
function groupHistory(history) {
  const loops = {};
  history.forEach(h => { if(h.loopId) { if(!loops[h.loopId]) loops[h.loopId]=[]; loops[h.loopId].push(h.id); } });
  return loops;
}

const LendingProtocolCard = ({proto, expanded, onToggle}) => {
  const [histFilter,setHistFilter] = useState("all");
  const loops = groupHistory(proto.history);
  const loopIds = {}; // id -> loopId
  proto.history.forEach(h => { if(h.loopId) loopIds[h.id] = h.loopId; });

  const hfColor = proto.healthFactor
    ? parseFloat(proto.healthFactor)>=2?C.green:parseFloat(proto.healthFactor)>=1.2?C.amber:C.red
    : C.sub;

  const filteredHistory = proto.history.filter(h =>
    histFilter==="all" || h.type===histFilter
  );

  return (
    <div style={{border:`1px solid ${proto.closed?C.border:C.borderHi}`,borderRadius:9,overflow:"hidden",
      background:proto.closed?C.bg:C.s1,opacity:proto.closed?.75:1}}>
      {/* Header */}
      <div onClick={onToggle} style={{display:"flex",alignItems:"center",gap:12,padding:"12px 14px",
        cursor:"pointer",transition:"background .1s"}}
        onMouseOver={e=>e.currentTarget.style.background=C.hov}
        onMouseOut={e=>e.currentTarget.style.background="transparent"}>

        {/* Protocol icon */}
        <div style={{width:36,height:36,borderRadius:8,flexShrink:0,
          background:`rgba(59,130,246,0.12)`,border:`1px solid rgba(59,130,246,0.25)`,
          display:"flex",alignItems:"center",justifyContent:"center",
          fontSize:12,fontWeight:800,color:C.blue,fontFamily:"'IBM Plex Mono',monospace"}}>
          {proto.protocol[0]}
        </div>

        <div style={{flex:1,minWidth:0}}>
          <div style={{display:"flex",alignItems:"center",gap:8,marginBottom:3}}>
            <span style={{fontWeight:700,fontSize:13}}>{proto.protocol}</span>
            <NetBadge net={proto.net}/>
            <Tag v="def">{proto.version}</Tag>
            {proto.closed&&<Tag v="def">Closed</Tag>}
          </div>
          {!proto.closed&&(
            <div style={{display:"flex",gap:16,alignItems:"center"}}>
              <span style={{fontSize:10,color:C.sub}}>
                Supply <span className="mono" style={{color:C.green}}>{fmt$(proto.supplyUsd)}</span>
              </span>
              <span style={{fontSize:10,color:C.sub}}>
                Borrow <span className="mono" style={{color:C.purple}}>{fmt$(proto.borrowUsd)}</span>
              </span>
              <span style={{fontSize:10,color:C.sub}}>
                Net <span className="mono" style={{color:C.text}}>{fmt$(proto.netExposureUsd)}</span>
              </span>
            </div>
          )}
        </div>

        {/* Health factor compact */}
        {!proto.closed&&(
          <div style={{width:140,flexShrink:0}}>
            <HealthBar hf={proto.healthFactor} compact/>
          </div>
        )}

        <div style={{transform:expanded?"rotate(180deg)":"none",transition:"transform .15s",color:C.sub}}>
          <Icon n="chev_d" s={13}/>
        </div>
      </div>

      {/* Expanded */}
      {expanded&&(
        <div className="aFade" style={{borderTop:`1px solid ${C.border}`}}>

          {/* Positions + Health */}
          {!proto.closed&&(
            <div style={{padding:"14px",display:"grid",gridTemplateColumns:"1fr 220px",gap:14}}>
              {/* Positions table */}
              <div>
                <div style={{fontSize:9,fontWeight:700,color:C.muted,textTransform:"uppercase",
                  letterSpacing:".1em",marginBottom:8}}>Positions</div>
                <div style={{display:"flex",flexDirection:"column",gap:4}}>
                  {/* Supply header */}
                  {proto.positions.filter(p=>p.type==="supply").length>0&&(
                    <div style={{fontSize:9,color:C.green,fontWeight:700,textTransform:"uppercase",
                      letterSpacing:".08em",padding:"2px 0",marginTop:2}}>Supply</div>
                  )}
                  {proto.positions.filter(p=>p.type==="supply").map((p,i)=>(
                    <div key={i} style={{display:"grid",gridTemplateColumns:"32px 1fr 90px 90px 80px 70px",
                      gap:6,padding:"7px 10px",background:C.s2,border:`1px solid ${C.greenMid}`,
                      borderRadius:6,alignItems:"center"}}>
                      <div style={{width:26,height:26,borderRadius:5,background:C.greenDim,
                        border:`1px solid ${C.greenMid}`,display:"flex",alignItems:"center",justifyContent:"center"}}>
                        <Icon n="arr_up" s={12} c={C.green}/>
                      </div>
                      <div>
                        <div style={{fontSize:12,fontWeight:600}}>{p.asset}</div>
                        {p.collateral&&<div style={{fontSize:8,color:C.amber,fontWeight:600,textTransform:"uppercase",letterSpacing:".06em"}}>collateral</div>}
                      </div>
                      <div className="mono" style={{fontSize:10,textAlign:"right",color:C.sub}}>{p.qty} {p.asset}</div>
                      <div className="mono" style={{fontSize:11,textAlign:"right"}}>{fmt$(p.usd)}</div>
                      <div className="mono" style={{fontSize:11,color:C.green,textAlign:"right"}}>{p.apy}%</div>
                      <div style={{textAlign:"right"}}><Tag v="green">Supply</Tag></div>
                    </div>
                  ))}

                  {/* Borrow header */}
                  {proto.positions.filter(p=>p.type==="borrow").length>0&&(
                    <div style={{fontSize:9,color:C.purple,fontWeight:700,textTransform:"uppercase",
                      letterSpacing:".08em",padding:"4px 0 2px",marginTop:4}}>Borrow</div>
                  )}
                  {proto.positions.filter(p=>p.type==="borrow").map((p,i)=>(
                    <div key={i} style={{display:"grid",gridTemplateColumns:"32px 1fr 90px 90px 80px 70px",
                      gap:6,padding:"7px 10px",background:C.s2,border:`1px solid ${C.purpleMid}`,
                      borderRadius:6,alignItems:"center"}}>
                      <div style={{width:26,height:26,borderRadius:5,background:C.purpleDim,
                        border:`1px solid ${C.purpleMid}`,display:"flex",alignItems:"center",justifyContent:"center"}}>
                        <Icon n="arr_dn" s={12} c={C.purple}/>
                      </div>
                      <div style={{fontSize:12,fontWeight:600}}>{p.asset}</div>
                      <div className="mono" style={{fontSize:10,textAlign:"right",color:C.sub}}>{p.qty} {p.asset}</div>
                      <div className="mono" style={{fontSize:11,textAlign:"right"}}>{fmt$(p.usd)}</div>
                      <div className="mono" style={{fontSize:11,color:C.red,textAlign:"right"}}>{p.apy}%</div>
                      <div style={{textAlign:"right"}}><Tag v="purple">Borrow</Tag></div>
                    </div>
                  ))}
                </div>
              </div>

              {/* Health factor big */}
              <div>
                <div style={{fontSize:9,fontWeight:700,color:C.muted,textTransform:"uppercase",
                  letterSpacing:".1em",marginBottom:8}}>Risk</div>
                <HealthBar hf={proto.healthFactor}/>
                <div style={{marginTop:10,display:"flex",flexDirection:"column",gap:4}}>
                  <div style={{display:"flex",justifyContent:"space-between",padding:"5px 9px",
                    background:C.s2,border:`1px solid ${C.border}`,borderRadius:5}}>
                    <span style={{fontSize:10,color:C.sub}}>Collateral ratio</span>
                    <span className="mono" style={{fontSize:10,color:C.text}}>{(proto.collateralRatio*100).toFixed(1)}%</span>
                  </div>
                  <div style={{display:"flex",justifyContent:"space-between",padding:"5px 9px",
                    background:C.s2,border:`1px solid ${C.border}`,borderRadius:5}}>
                    <span style={{fontSize:10,color:C.sub}}>Net exposure</span>
                    <span className="mono" style={{fontSize:10,color:C.text}}>{fmt$(proto.netExposureUsd)}</span>
                  </div>
                </div>
              </div>
            </div>
          )}

          {/* History */}
          <div style={{borderTop:`1px solid ${C.border}`,padding:"12px 14px"}}>
            <div style={{display:"flex",alignItems:"center",gap:8,marginBottom:10}}>
              <div style={{fontSize:9,fontWeight:700,color:C.muted,textTransform:"uppercase",letterSpacing:".1em"}}>
                Transaction history
              </div>
              <div style={{display:"flex",gap:4,marginLeft:"auto"}}>
                {["all","LEND_DEPOSIT","LEND_WITHDRAW","BORROW","REPAY"].map(f=>(
                  <button key={f} onClick={()=>setHistFilter(f)} style={{
                    padding:"2px 8px",borderRadius:12,fontSize:9,fontWeight:600,
                    background:histFilter===f?C.cyanDim:"transparent",
                    border:`1px solid ${histFilter===f?C.cyanMid:C.border}`,
                    color:histFilter===f?C.cyan:C.sub,transition:"all .1s"}}>
                    {f==="all"?"All":LEND_HISTORY_COLORS[f]?.label||f}
                  </button>
                ))}
              </div>
            </div>

            <div style={{display:"flex",flexDirection:"column",gap:2}}>
              {filteredHistory.map((h,hi) => {
                const def = LEND_HISTORY_COLORS[h.type]||{c:C.sub,bg:C.s2,label:h.type,icon:"arr_up"};
                const isLoop = !!h.loopId;
                const loopItems = isLoop ? loops[h.loopId] : [];
                const loopIdx = isLoop ? loopItems.indexOf(h.id) : -1;
                const isLoopFirst = loopIdx === 0;
                const isLoopMid   = loopIdx > 0 && loopIdx < loopItems.length-1;
                const isLoopLast  = loopIdx === loopItems.length-1;

                return (
                  <div key={h.id} style={{display:"flex",gap:0,alignItems:"stretch"}}>
                    {/* Loop connector */}
                    <div style={{width:20,flexShrink:0,display:"flex",flexDirection:"column",alignItems:"center",paddingTop:4}}>
                      {isLoop&&(
                        <>
                          {isLoopFirst&&<div style={{width:1,flex:1,background:C.amber+"60",marginTop:8}}/>}
                          {isLoopMid&&<div style={{width:1,height:"100%",background:C.amber+"60"}}/>}
                          {isLoopLast&&<div style={{width:1,flex:1,background:"transparent",marginTop:0}}/>}
                          {(isLoopFirst||isLoopMid)&&(
                            <div style={{position:"absolute",marginLeft:6,width:10,height:10,border:`1.5px solid ${C.amber}`,borderRadius:"50%",background:C.amberDim,flexShrink:0}}/>
                          )}
                        </>
                      )}
                    </div>

                    <div style={{flex:1,display:"flex",alignItems:"center",gap:8,
                      padding:"6px 10px",background:C.s2,border:`1px solid ${C.border}`,
                      borderRadius:6,marginBottom:2,
                      ...(isLoop?{borderColor:C.amberMid,background:C.amberDim+"50"}:{})}}>
                      <div style={{width:24,height:24,borderRadius:5,flexShrink:0,
                        background:def.bg,border:`1px solid ${def.c}30`,
                        display:"flex",alignItems:"center",justifyContent:"center"}}>
                        <Icon n={def.icon} s={11} c={def.c}/>
                      </div>
                      <div style={{flex:1}}>
                        <div style={{display:"flex",alignItems:"center",gap:6}}>
                          <Tag v={h.type==="LEND_DEPOSIT"?"green":h.type==="BORROW"?"purple":h.type==="REPAY"?"blue":"red"}>
                            {def.label}
                          </Tag>
                          <span style={{fontSize:11,fontWeight:600}}>{h.asset}</span>
                          {isLoop&&isLoopFirst&&(
                            <div style={{display:"flex",alignItems:"center",gap:3,
                              padding:"1px 6px",borderRadius:3,background:C.amberDim,
                              border:`1px solid ${C.amberMid}`,fontSize:8,fontWeight:700,color:C.amber}}>
                              <Icon n="loop" s={9} c={C.amber}/>LOOP
                            </div>
                          )}
                        </div>
                        <div className="mono" style={{fontSize:9,color:C.muted,marginTop:1}}>{h.hash}</div>
                      </div>
                      <div style={{textAlign:"right"}}>
                        <div className="mono" style={{fontSize:11,fontWeight:600,
                          color:h.qty.startsWith("+")?C.green:C.red}}>{h.qty} {h.asset}</div>
                        <div className="mono" style={{fontSize:9,color:C.sub}}>{h.usd}</div>
                      </div>
                      <div className="mono" style={{fontSize:9,color:C.muted,width:70,textAlign:"right",flexShrink:0}}>{h.date}</div>
                    </div>
                  </div>
                );
              })}
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

const LendingPage = () => {
  const [showClosed, setShowClosed] = useState(false);
  const [expandedId, setExpandedId] = useState("aave-eth");

  const active = LENDING_PROTOCOLS.filter(p => !p.closed);
  const closed = LENDING_PROTOCOLS.filter(p => p.closed);
  const displayed = showClosed ? [...active,...closed] : active;

  const totalSupply  = active.reduce((s,p)=>s+p.supplyUsd, 0);
  const totalBorrow  = active.reduce((s,p)=>s+p.borrowUsd, 0);
  const netExposure  = active.reduce((s,p)=>s+p.netExposureUsd, 0);

  return (
    <div style={{display:"flex",flexDirection:"column",height:"100%",overflow:"hidden"}}>
      {/* Summary stats */}
      <div style={{flexShrink:0,padding:"12px 16px",borderBottom:`1px solid ${C.border}`,
        display:"flex",gap:0,background:C.s1}}>
        <div style={{flex:1,display:"flex",gap:24,alignItems:"center"}}>
          <Stat label="Total supplied" value={fmt$(totalSupply)} color={C.green}/>
          <Stat label="Total borrowed" value={fmt$(totalBorrow)} color={C.purple}/>
          <Stat label="Net exposure"   value={fmt$(netExposure)} color={C.text}/>
          <Stat label="Protocols" value={active.length} sub={`${closed.length} closed`} color={C.text}/>
        </div>
        {/* Show closed toggle */}
        <label style={{display:"flex",alignItems:"center",gap:8,cursor:"pointer",userSelect:"none",
          padding:"0 12px",borderLeft:`1px solid ${C.border}`}}>
          <div style={{position:"relative",width:30,height:17,flexShrink:0}}>
            <input type="checkbox" checked={showClosed} onChange={e=>setShowClosed(e.target.checked)}
              style={{opacity:0,width:0,height:0}}/>
            <div onClick={()=>setShowClosed(s=>!s)} style={{
              position:"absolute",inset:0,background:showClosed?C.cyanMid:C.border,
              borderRadius:9,cursor:"pointer",transition:".2s"}}>
              <div style={{position:"absolute",width:11,height:11,top:3,
                left:showClosed?16:3,background:showClosed?C.cyan:C.sub,
                borderRadius:"50%",transition:".2s"}}/>
            </div>
          </div>
          <span style={{fontSize:11,color:C.sub,whiteSpace:"nowrap"}}>Show closed ({closed.length})</span>
        </label>
      </div>

      {/* Protocol cards */}
      <div style={{flex:1,overflowY:"auto",padding:"14px 16px",display:"flex",flexDirection:"column",gap:8}}>
        {displayed.map(proto=>(
          <LendingProtocolCard key={proto.id} proto={proto}
            expanded={expandedId===proto.id}
            onToggle={()=>setExpandedId(expandedId===proto.id?null:proto.id)}/>
        ))}
      </div>
    </div>
  );
};

/* ═══════════════════════════════════════════════════════════
   LP RANGE CHART (canvas-based)
═══════════════════════════════════════════════════════════ */
const RangeChart = ({pos}) => {
  const canvasRef = useRef();
  useEffect(()=>{
    if(!canvasRef.current||!pos.priceLow) return;
    const cv=canvasRef.current;
    const dpr=window.devicePixelRatio||1;
    const W=cv.clientWidth,H=cv.clientHeight;
    cv.width=W*dpr;cv.height=H*dpr;
    const ctx=cv.getContext("2d");ctx.scale(dpr,dpr);

    const pad={l:48,r:24,t:16,b:28};
    const IW=W-pad.l-pad.r;

    // price range
    const margin=0.3;
    const pMin=pos.priceLow*(1-margin);
    const pMax=pos.priceHigh*(1+margin);
    const pRange=pMax-pMin;
    const px=v=>pad.l+(v-pMin)/pRange*IW;

    // Simulated price curve (simple random walk for demo)
    const pts=[];
    for(let i=0;i<=100;i++){
      const t=i/100;
      const noise=Math.sin(t*8)*80+Math.cos(t*5)*50+Math.sin(t*15)*30;
      const p=pos.priceLow+(pos.priceHigh-pos.priceLow)*0.5+noise;
      pts.push({x:IW/100*i+pad.l,y:H/2});
    }

    // Range band
    const lx=px(pos.priceLow),rx=px(pos.priceHigh);
    ctx.fillStyle=pos.inRange?"rgba(52,211,153,0.08)":"rgba(248,113,113,0.06)";
    ctx.fillRect(lx,pad.t,rx-lx,H-pad.t-pad.b);
    ctx.strokeStyle=pos.inRange?C.green+"44":C.red+"44";
    ctx.lineWidth=1;
    ctx.setLineDash([4,3]);
    ctx.beginPath();ctx.moveTo(lx,pad.t);ctx.lineTo(lx,H-pad.b);ctx.stroke();
    ctx.beginPath();ctx.moveTo(rx,pad.t);ctx.lineTo(rx,H-pad.b);ctx.stroke();
    ctx.setLineDash([]);

    // Price axis ticks
    const ticks=5;
    for(let i=0;i<=ticks;i++){
      const v=pMin+(i/ticks)*pRange;
      const x=pad.l+(i/ticks)*IW;
      ctx.fillStyle="rgba(74,88,120,.5)";
      ctx.font=`${8}px IBM Plex Mono,monospace`;
      ctx.textAlign="center";
      ctx.fillText("$"+Math.round(v),x,H-8);
      ctx.strokeStyle="rgba(255,255,255,.04)";ctx.lineWidth=1;
      ctx.beginPath();ctx.moveTo(x,pad.t);ctx.lineTo(x,H-pad.b);ctx.stroke();
    }

    // Simulated "liquidity" histogram in range
    for(let x=lx;x<rx;x+=3){
      const dist=1-Math.abs((x-(lx+rx)/2)/((rx-lx)/2));
      const h=(H-pad.t-pad.b)*dist*0.7;
      ctx.fillStyle=pos.inRange?"rgba(52,211,153,0.25)":"rgba(248,113,113,0.15)";
      ctx.fillRect(x,H-pad.b-h,2.5,h);
    }

    // Current price line
    if(pos.currentPrice){
      const cx=px(pos.currentPrice);
      ctx.strokeStyle=pos.inRange?C.green:C.red;
      ctx.lineWidth=2;
      ctx.beginPath();ctx.moveTo(cx,pad.t);ctx.lineTo(cx,H-pad.b);ctx.stroke();
      // Price label
      ctx.fillStyle=pos.inRange?C.green:C.red;
      ctx.font=`700 ${9}px DM Sans,sans-serif`;
      ctx.textAlign="center";
      const lbl="$"+pos.currentPrice.toLocaleString();
      const tw=ctx.measureText(lbl).width+10;
      ctx.fillStyle=pos.inRange?C.greenDim:C.redDim;
      ctx.beginPath();ctx.roundRect(cx-tw/2,pad.t-2,tw,14,2);ctx.fill();
      ctx.fillStyle=pos.inRange?C.green:C.red;
      ctx.fillText(lbl,cx,pad.t+10);
    }

    // Labels for range bounds
    ctx.fillStyle="rgba(74,88,120,.8)";ctx.font=`${9}px IBM Plex Mono`;
    ctx.textAlign="center";
    ctx.fillText("Low $"+pos.priceLow,lx,pad.t+6);
    ctx.fillText("High $"+pos.priceHigh,rx,pad.t+6);
  },[pos]);

  if(!pos.priceLow) return (
    <div style={{height:100,display:"flex",alignItems:"center",justifyContent:"center",
      background:C.s2,borderRadius:6,border:`1px solid ${C.border}`}}>
      <span style={{fontSize:11,color:C.sub}}>Full range — no concentrated liquidity</span>
    </div>
  );

  return <canvas ref={canvasRef} style={{width:"100%",height:120,display:"block",borderRadius:6}}/>;
};

/* ═══════════════════════════════════════════════════════════
   LP POSITION CARD
═══════════════════════════════════════════════════════════ */
const LPCard = ({pos,onClick}) => {
  const pnlColor = pos.pnlNet>=0?C.green:C.red;
  const inRange  = pos.inRange;
  const isClosed = pos.status==="closed";

  return (
    <div onClick={onClick} style={{
      border:`1px solid ${inRange&&!isClosed?C.greenMid:isClosed?C.border:C.redMid}`,
      borderRadius:9,overflow:"hidden",background:C.s1,cursor:"pointer",transition:"border-color .15s,background .1s"}}
      onMouseOver={e=>e.currentTarget.style.background=C.hov}
      onMouseOut={e=>e.currentTarget.style.background=C.s1}>

      {/* Status bar top */}
      <div style={{height:3,background:inRange&&!isClosed?C.green:isClosed?C.border:C.red}}/>

      <div style={{padding:"12px 14px"}}>
        {/* Header */}
        <div style={{display:"flex",alignItems:"flex-start",justifyContent:"space-between",marginBottom:10}}>
          <div>
            <div style={{display:"flex",alignItems:"center",gap:8,marginBottom:3}}>
              <span style={{fontWeight:800,fontSize:14}}>{pos.pair}</span>
              <Tag v={isClosed?"def":inRange?"green":"red"}>{isClosed?"Closed":inRange?"In range":"Out of range"}</Tag>
              {pos.nftId&&<span className="mono" style={{fontSize:8,color:C.muted}}>{pos.nftId}</span>}
            </div>
            <div style={{display:"flex",gap:8,alignItems:"center",fontSize:10,color:C.sub}}>
              <span style={{fontWeight:600,color:C.text}}>{pos.protocol}</span>
              <NetBadge net={pos.net}/>
              <span>Fee {pos.fee*100}%</span>
              {pos.priceToken&&!isClosed&&(
                <span className="mono" style={{fontSize:9,color:C.muted}}>{pos.priceToken}</span>
              )}
            </div>
          </div>
          <div style={{textAlign:"right"}}>
            <div className="mono" style={{fontSize:16,fontWeight:700,color:isClosed?C.sub:C.text}}>
              {isClosed?"—":fmt$(pos.totalUsd)}
            </div>
            <div style={{fontSize:9,color:C.sub}}>current value</div>
          </div>
        </div>

        {/* Range visualization — mini */}
        {pos.priceLow&&!isClosed&&(
          <div style={{marginBottom:10}}>
            <div style={{position:"relative",height:8,background:C.s2,borderRadius:4,overflow:"hidden"}}>
              {/* Range band */}
              {(()=>{
                const margin=0.25;
                const pMin=pos.priceLow*(1-margin);
                const pMax=pos.priceHigh*(1+margin);
                const pRange=pMax-pMin;
                const lPct=((pos.priceLow-pMin)/pRange)*100;
                const rPct=((pos.priceHigh-pMin)/pRange)*100;
                const cPct=((pos.currentPrice-pMin)/pRange)*100;
                return (
                  <>
                    <div style={{position:"absolute",left:`${lPct}%`,width:`${rPct-lPct}%`,height:"100%",
                      background:inRange?"rgba(52,211,153,.3)":"rgba(248,113,113,.2)"}}/>
                    <div style={{position:"absolute",left:`${cPct}%`,top:0,width:2,height:"100%",
                      background:inRange?C.green:C.red,transform:"translateX(-1px)"}}/>
                  </>
                );
              })()}
            </div>
            <div style={{display:"flex",justifyContent:"space-between",marginTop:3,fontSize:8,color:C.muted}}>
              <span className="mono">${pos.priceLow}</span>
              <span className="mono" style={{color:inRange?C.green:C.red}}>current ${pos.currentPrice}</span>
              <span className="mono">${pos.priceHigh}</span>
            </div>
          </div>
        )}

        {/* Metrics grid */}
        <div style={{display:"grid",gridTemplateColumns:"repeat(4,1fr)",gap:6}}>
          {[
            {l:"Fees earned",  v:fmt$(pos.feesEarned),      c:C.green},
            {l:"Imp. loss",    v:fmtPct(pos.il),             c:pos.il>=0?C.green:C.red, sub:fmt$(pos.ilUsd)},
            {l:"Total P&L",    v:fmtPct(pos.pnl),            c:pnlColor, sub:fmt$(pos.pnlNet)+" net"},
            {l:"Entered",      v:pos.entered,                 c:C.sub, mono:true, small:true},
          ].map(m=>(
            <div key={m.l} style={{background:C.s2,borderRadius:5,padding:"7px 9px"}}>
              <div style={{fontSize:8,color:C.muted,textTransform:"uppercase",letterSpacing:".07em",marginBottom:3}}>{m.l}</div>
              <div className={m.mono?"mono":""} style={{fontSize:m.small?10:12,fontWeight:700,color:m.c}}>{m.v}</div>
              {m.sub&&<div className="mono" style={{fontSize:8,color:C.sub,marginTop:1}}>{m.sub}</div>}
            </div>
          ))}
        </div>

        {/* Drill down hint */}
        <div style={{display:"flex",justifyContent:"flex-end",alignItems:"center",marginTop:8,
          gap:4,fontSize:10,color:C.muted}}>
          <Icon n="chev_r" s={10} c={C.muted}/>View details
        </div>
      </div>
    </div>
  );
};

/* ═══════════════════════════════════════════════════════════
   LP POSITION DETAIL PANEL (slide-over)
═══════════════════════════════════════════════════════════ */
const TX_COLORS = {
  LP_ENTRY:      {c:C.purple, label:"Add liquidity",  icon:"arr_dn"},
  LP_EXIT_FINAL: {c:C.red,    label:"Remove all",     icon:"arr_up"},
  LP_EXIT:       {c:C.red,    label:"Remove partial", icon:"arr_up"},
  LP_FEE_CLAIM:  {c:C.green,  label:"Claim fees",     icon:"fee"},
};

const LPDetailPanel = ({pos,onClose}) => {
  if(!pos) return null;
  const isClosed = pos.status==="closed";

  return (
    <div style={{position:"absolute",inset:0,zIndex:200,display:"flex"}}>
      {/* Backdrop */}
      <div style={{flex:1,background:"rgba(0,0,0,.5)"}} onClick={onClose}/>
      {/* Panel */}
      <div className="aSlide" style={{
        width:460,background:C.s1,borderLeft:`1px solid ${C.borderHi}`,
        display:"flex",flexDirection:"column",height:"100%",overflow:"hidden",
        boxShadow:"-20px 0 60px rgba(0,0,0,.6)",
      }}>
        {/* Header */}
        <div style={{padding:"14px 16px 12px",borderBottom:`1px solid ${C.border}`,flexShrink:0}}>
          <div style={{display:"flex",alignItems:"flex-start",justifyContent:"space-between",gap:10}}>
            <div>
              <div style={{display:"flex",alignItems:"center",gap:8,marginBottom:3}}>
                <span style={{fontWeight:800,fontSize:15}}>{pos.pair}</span>
                <Tag v={isClosed?"def":pos.inRange?"green":"red"}>
                  {isClosed?"Closed":pos.inRange?"In range":"Out of range"}
                </Tag>
              </div>
              <div style={{fontSize:11,color:C.sub,display:"flex",gap:8}}>
                <span>{pos.protocol}</span>
                <NetBadge net={pos.net}/>
                <span>Fee {pos.fee*100}%</span>
                {pos.nftId&&<span className="mono">{pos.nftId}</span>}
              </div>
            </div>
            <button onClick={onClose} style={{padding:4,color:C.sub,display:"flex",borderRadius:4}}>
              <Icon n="x" s={14}/>
            </button>
          </div>
        </div>

        <div style={{flex:1,overflowY:"auto",padding:"14px 16px 32px",display:"flex",flexDirection:"column",gap:16}}>

          {/* Current value / tokens */}
          {!isClosed&&(
            <div style={{background:C.s2,border:`1px solid ${C.borderHi}`,borderRadius:8,padding:"12px 14px"}}>
              <div style={{fontSize:9,fontWeight:700,color:C.muted,textTransform:"uppercase",
                letterSpacing:".1em",marginBottom:10}}>Current position</div>
              <div style={{display:"grid",gridTemplateColumns:"1fr 1fr",gap:8,marginBottom:10}}>
                {[pos.token0,pos.token1].map(tk=>(
                  <div key={tk.sym} style={{background:C.s3,borderRadius:6,padding:"9px 11px"}}>
                    <div style={{fontSize:9,color:C.sub,marginBottom:4}}>{tk.sym}</div>
                    <div className="mono" style={{fontSize:14,fontWeight:700}}>{tk.qty} <span style={{fontSize:10,color:C.sub}}>{tk.sym}</span></div>
                    <div className="mono" style={{fontSize:10,color:C.sub}}>{fmt$(tk.usd)}</div>
                  </div>
                ))}
              </div>
              <div style={{display:"flex",justifyContent:"space-between",alignItems:"baseline"}}>
                <span style={{fontSize:11,color:C.sub}}>Total</span>
                <span className="mono" style={{fontSize:16,fontWeight:800}}>{fmt$(pos.totalUsd)}</span>
              </div>
            </div>
          )}

          {/* Range chart */}
          <div>
            <div style={{fontSize:9,fontWeight:700,color:C.muted,textTransform:"uppercase",
              letterSpacing:".1em",marginBottom:8}}>Price range</div>
            <RangeChart pos={pos}/>
            {pos.priceLow&&(
              <div style={{display:"grid",gridTemplateColumns:"1fr 1fr 1fr",gap:6,marginTop:8}}>
                {[
                  {l:"Lower bound", v:"$"+pos.priceLow, c:C.sub},
                  {l:"Current price", v:pos.currentPrice?"$"+pos.currentPrice.toLocaleString():"—",
                    c:pos.inRange?C.green:C.red},
                  {l:"Upper bound", v:"$"+pos.priceHigh, c:C.sub},
                ].map(m=>(
                  <div key={m.l} style={{background:C.s2,borderRadius:5,padding:"7px 9px",textAlign:"center"}}>
                    <div style={{fontSize:8,color:C.muted,textTransform:"uppercase",letterSpacing:".07em",marginBottom:3}}>{m.l}</div>
                    <div className="mono" style={{fontSize:12,fontWeight:700,color:m.c}}>{m.v}</div>
                  </div>
                ))}
              </div>
            )}
          </div>

          {/* P&L breakdown */}
          <div style={{background:C.s2,border:`1px solid ${C.borderHi}`,borderRadius:8,padding:"12px 14px"}}>
            <div style={{fontSize:9,fontWeight:700,color:C.muted,textTransform:"uppercase",
              letterSpacing:".1em",marginBottom:10}}>P&L breakdown</div>
            <div style={{display:"flex",flexDirection:"column",gap:5}}>
              {[
                {l:"Fees earned",      v:fmt$(pos.feesEarned), c:C.green,  note:"Accumulated since entry"},
                {l:"Impermanent loss", v:fmt$(pos.ilUsd),      c:pos.ilUsd>=0?C.green:C.red, note:fmtPct(pos.il)+" vs HODL"},
                {l:"Price appreciation",v:fmt$(pos.pnlUsd-pos.feesEarned-Math.abs(pos.ilUsd)), c:C.text, note:"Asset price change"},
              ].map(m=>(
                <div key={m.l} style={{display:"flex",justifyContent:"space-between",padding:"6px 9px",
                  background:C.s3,borderRadius:5,alignItems:"center"}}>
                  <div>
                    <div style={{fontSize:11}}>{m.l}</div>
                    {m.note&&<div style={{fontSize:9,color:C.muted}}>{m.note}</div>}
                  </div>
                  <span className="mono" style={{fontSize:12,fontWeight:700,color:m.c}}>{m.v}</span>
                </div>
              ))}
              <div style={{height:1,background:C.border,margin:"2px 0"}}/>
              <div style={{display:"flex",justifyContent:"space-between",padding:"6px 9px",
                background:pos.pnlNet>=0?C.greenDim:C.redDim,
                border:`1px solid ${pos.pnlNet>=0?C.greenMid:C.redMid}`,borderRadius:5}}>
                <span style={{fontSize:12,fontWeight:700}}>Net P&L</span>
                <span className="mono" style={{fontSize:13,fontWeight:800,
                  color:pos.pnlNet>=0?C.green:C.red}}>{fmt$(pos.pnlNet)}</span>
              </div>
            </div>
          </div>

          {/* Transaction history */}
          <div>
            <div style={{fontSize:9,fontWeight:700,color:C.muted,textTransform:"uppercase",
              letterSpacing:".1em",marginBottom:8}}>All transactions</div>
            <div style={{display:"flex",flexDirection:"column",gap:4}}>
              {pos.txns.map((tx,i)=>{
                const def=TX_COLORS[tx.type]||{c:C.sub,label:tx.type,icon:"arr_up"};
                return (
                  <div key={i} style={{display:"flex",alignItems:"center",gap:9,padding:"8px 10px",
                    background:C.s2,border:`1px solid ${C.border}`,borderRadius:6}}>
                    <div style={{width:26,height:26,borderRadius:5,flexShrink:0,
                      background:def.c+"15",border:`1px solid ${def.c}30`,
                      display:"flex",alignItems:"center",justifyContent:"center"}}>
                      <Icon n={def.icon} s={12} c={def.c}/>
                    </div>
                    <div style={{flex:1}}>
                      <div style={{fontSize:11,fontWeight:600}}>{def.label}</div>
                      {(tx.qty0||tx.qty1)&&(
                        <div style={{fontSize:9,color:C.sub}}>{tx.qty0} {tx.qty1?("· "+tx.qty1):""}</div>
                      )}
                      {tx.fees&&<div style={{fontSize:9,color:C.green}}>{tx.fees}</div>}
                    </div>
                    <div style={{textAlign:"right"}}>
                      <div className="mono" style={{fontSize:9,color:C.muted}}>{tx.date}</div>
                      {tx.hash&&(
                        <div style={{display:"flex",alignItems:"center",gap:3,justifyContent:"flex-end",marginTop:2}}>
                          <span className="mono" style={{fontSize:8,color:C.muted}}>{tx.hash}</span>
                          <Icon n="external" s={9} c={C.muted}/>
                        </div>
                      )}
                    </div>
                  </div>
                );
              })}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
};

/* ═══════════════════════════════════════════════════════════
   LP PAGE
═══════════════════════════════════════════════════════════ */
const LPPage = () => {
  const [filter,    setFilter]    = useState("active"); // active | closed | all
  const [detail,    setDetail]    = useState(null);

  const active  = LP_POSITIONS.filter(p=>p.status==="active");
  const closed  = LP_POSITIONS.filter(p=>p.status==="closed");
  const displayed = filter==="active"?active:filter==="closed"?closed:LP_POSITIONS;

  const totalActive  = active.reduce((s,p)=>s+p.totalUsd,0);
  const totalFees    = LP_POSITIONS.reduce((s,p)=>s+p.feesEarned,0);
  const inRangeCount = active.filter(p=>p.inRange).length;
  const outRangeCount= active.filter(p=>!p.inRange).length;

  return (
    <div style={{display:"flex",flexDirection:"column",height:"100%",overflow:"hidden",position:"relative"}}>
      {/* Summary */}
      <div style={{flexShrink:0,padding:"12px 16px",borderBottom:`1px solid ${C.border}`,
        background:C.s1,display:"flex",gap:24,alignItems:"center"}}>
        <Stat label="Active TVL"     value={fmt$(totalActive)}  color={C.text}/>
        <Stat label="Total fees"     value={fmt$(totalFees)}    color={C.green}/>
        <Stat label="In range"       value={inRangeCount}       color={C.green} sub="active positions"/>
        <Stat label="Out of range"   value={outRangeCount}      color={outRangeCount>0?C.red:C.sub} sub="need attention"/>
      </div>

      {/* Filter tabs */}
      <div style={{flexShrink:0,padding:"8px 16px",borderBottom:`1px solid ${C.border}`,
        display:"flex",gap:6,alignItems:"center"}}>
        {[
          {id:"active",label:`Active (${active.length})`},
          {id:"closed",label:`Closed (${closed.length})`},
          {id:"all",   label:`All (${LP_POSITIONS.length})`},
        ].map(f=>(
          <button key={f.id} onClick={()=>setFilter(f.id)} style={{
            padding:"4px 12px",borderRadius:20,fontSize:11,fontWeight:600,
            background:filter===f.id?C.cyanDim:"transparent",
            border:`1px solid ${filter===f.id?C.cyanMid:C.border}`,
            color:filter===f.id?C.cyan:C.sub,transition:"all .1s"}}>
            {f.label}
          </button>
        ))}
        {outRangeCount>0&&filter==="active"&&(
          <div style={{marginLeft:"auto",display:"flex",alignItems:"center",gap:5,padding:"4px 10px",
            background:C.redDim,border:`1px solid ${C.redMid}`,borderRadius:5}}>
            <Icon n="warn" s={11} c={C.red}/>
            <span style={{fontSize:10,color:C.red,fontWeight:600}}>
              {outRangeCount} position{outRangeCount>1?"s":""} out of range
            </span>
          </div>
        )}
      </div>

      {/* Cards */}
      <div style={{flex:1,overflowY:"auto",padding:"14px 16px",
        display:"grid",gridTemplateColumns:"repeat(auto-fill,minmax(380px,1fr))",
        gap:10,alignContent:"start"}}>
        {displayed.map(pos=>(
          <LPCard key={pos.id} pos={pos} onClick={()=>setDetail(pos)}/>
        ))}
      </div>

      {/* Detail panel */}
      {detail&&<LPDetailPanel pos={detail} onClose={()=>setDetail(null)}/>}
    </div>
  );
};

/* ═══════════════════════════════════════════════════════════
   MAIN SHELL
═══════════════════════════════════════════════════════════ */
const NAV = [
  {id:"tokens",  icon:"tokens",  label:"Tokens",  color:C.cyan},
  {id:"lp",      icon:"lp",      label:"LP",       color:C.blue},
  {id:"lending", icon:"lending", label:"Lending",  color:C.green},
  {id:"staking", icon:"staking", label:"Staking",  color:C.amber, soon:true},
];

const SIDEBAR_FILTERS = {
  lp:[
    {id:"w1",type:"wallet",label:"Main",  addr:"0xd8dA…6045",color:C.cyan},
    {id:"w2",type:"wallet",label:"DeFi",  addr:"0x47ac…b503",color:C.purple},
    {label:"Network",type:"sep"},
    {id:"ETH",type:"net",icon:"⟠",label:"Ethereum",color:"#627EEA"},
    {id:"ARB",type:"net",icon:"△",label:"Arbitrum",color:"#28A0F0"},
    {id:"BASE",type:"net",icon:"◆",label:"Base",color:"#0052FF"},
    {id:"OP",type:"net",icon:"○",label:"Optimism",color:"#FF0420"},
  ],
  lending:[
    {id:"w1",type:"wallet",label:"Main",  addr:"0xd8dA…6045",color:C.cyan},
    {id:"w2",type:"wallet",label:"DeFi",  addr:"0x47ac…b503",color:C.purple},
    {label:"Protocol",type:"sep"},
    {id:"aave",type:"proto",label:"Aave V3"},
    {id:"compound",type:"proto",label:"Compound"},
  ],
};

const Sidebar = ({page}) => {
  const [sel,setSel] = useState([]);
  const items = SIDEBAR_FILTERS[page]||[];
  const toggle = id => setSel(s=>s.includes(id)?s.filter(x=>x!==id):[...s,id]);
  const cnt = sel.length;

  return (
    <div style={{width:182,flexShrink:0,borderRight:`1px solid ${C.border}`,background:C.s1,
      display:"flex",flexDirection:"column",overflow:"hidden"}}>
      <div style={{padding:"9px 12px 7px",borderBottom:`1px solid ${C.border}`,flexShrink:0}}>
        <div style={{display:"flex",alignItems:"center",justifyContent:"space-between"}}>
          <span style={{fontSize:9,fontWeight:700,color:C.muted,textTransform:"uppercase",letterSpacing:".12em"}}>
            Filters
          </span>
          {cnt>0&&(
            <button onClick={()=>setSel([])} style={{fontSize:9,color:C.sub,padding:"1px 5px",
              border:`1px solid ${C.border}`,borderRadius:3,background:C.s2}}>clear {cnt}</button>
          )}
        </div>
      </div>
      <div style={{flex:1,overflowY:"auto",padding:"8px 0"}}>
        {items.map((item,i)=>{
          if(item.type==="sep") return (
            <div key={i} style={{fontSize:8,fontWeight:700,color:C.muted,textTransform:"uppercase",
              letterSpacing:".12em",padding:"10px 12px 4px"}}>{item.label}</div>
          );
          const on=sel.includes(item.id);
          return (
            <button key={item.id} onClick={()=>toggle(item.id)} style={{
              width:"100%",display:"flex",alignItems:"center",gap:7,padding:"5px 12px",
              background:on?item.color+"15":"transparent",
              border:"none",
              borderLeft:`2px solid ${on?item.color:"transparent"}`,
              color:on?item.color:C.sub,fontSize:11,fontWeight:on?600:400,
              textAlign:"left",transition:"all .12s"}}>
              {item.type==="wallet"&&<div style={{width:7,height:7,borderRadius:"50%",background:item.color,flexShrink:0}}/>}
              {item.type==="net"&&<span style={{fontSize:13,color:item.color}}>{item.icon}</span>}
              {item.type==="proto"&&<div style={{width:7,height:7,borderRadius:2,background:C.blue,flexShrink:0}}/>}
              <div style={{flex:1,overflow:"hidden"}}>
                <div style={{overflow:"hidden",textOverflow:"ellipsis",whiteSpace:"nowrap"}}>{item.label}</div>
                {item.addr&&<div className="mono" style={{fontSize:8,color:C.muted}}>{item.addr}</div>}
              </div>
            </button>
          );
        })}
      </div>
    </div>
  );
};

export default function App() {
  const [page, setPage] = useState("lending");

  return (
    <div style={{height:"100vh",display:"flex",flexDirection:"column",background:C.bg,overflow:"hidden"}}>
      <style>{G}</style>

      {/* TOP BAR */}
      <div style={{flexShrink:0,height:46,borderBottom:`1px solid ${C.border}`,background:C.s1,
        display:"flex",alignItems:"center",gap:12,padding:"0 14px"}}>
        <div style={{display:"flex",alignItems:"center",gap:7}}>
          <div style={{width:24,height:24,background:C.cyan,borderRadius:5,
            display:"flex",alignItems:"center",justifyContent:"center",fontSize:12}}>⚡</div>
          <span style={{fontWeight:800,fontSize:13,letterSpacing:"-.02em"}}>WalletRadar</span>
        </div>
        <div style={{width:1,height:16,background:C.border}}/>
        {[{l:"Portfolio",v:"$47.2k",c:C.text},{l:"Unrealised",v:"+7.4%",c:C.green},{l:"Realised",v:"+$1.9k",c:C.green}]
          .map(m=>(
            <div key={m.l} style={{display:"flex",gap:5,alignItems:"baseline"}}>
              <span style={{fontSize:9,color:C.sub}}>{m.l}</span>
              <span className="mono" style={{fontSize:13,fontWeight:700,color:m.c}}>{m.v}</span>
            </div>
          ))}
        <div style={{flex:1}}/>
        {[{a:"0xd8dA…6045",l:"Main",c:C.cyan},{a:"0x47ac…b503",l:"DeFi",c:C.purple}].map((w,i)=>(
          <div key={i} style={{display:"flex",alignItems:"center",gap:4,padding:"3px 8px",
            background:C.s2,border:`1px solid ${C.border}`,borderRadius:5}}>
            <div style={{width:6,height:6,borderRadius:"50%",background:w.c}}/>
            <span className="mono" style={{fontSize:9,color:C.sub}}>{w.a} · {w.l}</span>
          </div>
        ))}
      </div>

      {/* BODY */}
      <div style={{flex:1,display:"flex",overflow:"hidden"}}>

        {/* ICON NAV */}
        <div style={{width:44,flexShrink:0,borderRight:`1px solid ${C.border}`,
          display:"flex",flexDirection:"column",alignItems:"center",padding:"6px 0",gap:2}}>
          {NAV.map(s=>(
            <button key={s.id} onClick={()=>!s.soon&&setPage(s.id)}
              title={s.label+(s.soon?" (soon)":"")}
              style={{
                width:36,height:36,borderRadius:7,display:"flex",alignItems:"center",justifyContent:"center",
                background:page===s.id?s.color+"20":"transparent",
                border:`1px solid ${page===s.id?s.color+"44":"transparent"}`,
                color:page===s.id?s.color:s.soon?C.muted:C.sub,
                opacity:s.soon?.35:1,cursor:s.soon?"not-allowed":"pointer",
                transition:"all .12s",position:"relative",
              }}>
              <Icon n={s.icon} s={15} c={page===s.id?s.color:s.soon?C.muted:C.sub}/>
              {page===s.id&&<div style={{position:"absolute",left:0,top:"20%",width:2,
                height:"60%",background:s.color,borderRadius:"0 2px 2px 0"}}/>}
            </button>
          ))}
          <div style={{flex:1}}/>
          <div style={{paddingBottom:4}}>
            <button title="Settings" style={{width:36,height:36,borderRadius:7,
              display:"flex",alignItems:"center",justifyContent:"center",color:C.sub}}>
              <Icon n="gear" s={15} c={C.sub}/>
            </button>
          </div>
        </div>

        {/* SIDEBAR */}
        {(page==="lp"||page==="lending")&&<Sidebar page={page}/>}

        {/* MAIN CONTENT */}
        <div style={{flex:1,display:"flex",flexDirection:"column",overflow:"hidden",position:"relative"}}>
          {/* Page header */}
          <div style={{flexShrink:0,padding:"7px 14px",borderBottom:`1px solid ${C.border}`,
            display:"flex",alignItems:"center",gap:7}}>
            <Icon n={page} s={12} c={NAV.find(n=>n.id===page)?.color||C.sub}/>
            <span style={{fontWeight:700,fontSize:12,color:NAV.find(n=>n.id===page)?.color||C.text}}>
              {NAV.find(n=>n.id===page)?.label}
            </span>
            {page==="lending"&&(
              <div style={{marginLeft:"auto",display:"flex",alignItems:"center",gap:5,
                padding:"3px 9px",background:C.greenDim,border:`1px solid ${C.greenMid}`,borderRadius:5}}>
                <Icon n="health" s={11} c={C.green}/>
                <span style={{fontSize:10,color:C.green,fontWeight:600}}>All positions healthy</span>
              </div>
            )}
          </div>

          {page==="lending"&&<LendingPage/>}
          {page==="lp"&&<LPPage/>}
          {page==="tokens"&&(
            <div style={{flex:1,display:"flex",alignItems:"center",justifyContent:"center",color:C.sub,fontSize:12}}>
              Tokens view — click Lending or LP in the sidebar
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
