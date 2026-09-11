"""Restricted evaluation of reference temperature formatting, not vendor executable code."""
from extract_syu_air import Facts, Unknown, Return
from javalang import tree as T
import re

class FormatFacts(Facts):
    def __init__(self,profile,constants,env,methods,raw,unit,field):
        super().__init__(profile,constants,env)
        self.methods=methods;self.raw=raw;self.unit=unit;self.field=field;self.output={};self.depth=0
    def expr(self,n):
        if isinstance(n,T.Literal) and re.match(r'^\d+\.\d+[fFdD]?$',n.value):return float(n.value.rstrip('fFdD'))
        if isinstance(n,T.MemberReference) and n.qualifier=='DataCanbus' and n.member=='DATA':
            index=self.expr(n.selectors[0].index)
            if index==1000:return self.profile
            if index==self.env.get(self.field):return self.raw
            if index==self.env.get('U_AIR_TEMP_UNIT') and index!=255:return self.unit
            raise Unknown('format live data')
        if isinstance(n,T.MemberReference) and n.qualifier in ('R.id','R.drawable','R.color','R.string'):return n.member
        if isinstance(n,T.BinaryOperation) and n.operator in ('+','/'):
            a=self.expr(n.operandl);b=self.expr(n.operandr)
            if n.operator=='+':
                if isinstance(a,str) or isinstance(b,str):return str(a)+str(b)
                return a+b
            return a/b if isinstance(a,float) or isinstance(b,float) else int(a/b)
        if isinstance(n,T.ClassCreator) and n.type.name=='StringBuilder':r=''
        elif isinstance(n,T.Cast):r=self.expr(n.expression)
        elif isinstance(n,T.MethodInvocation):
            if n.member=='findViewById':r=('view',self.expr(n.arguments[0]))
            elif n.qualifier=='String' and n.member=='valueOf':r=str(self.expr(n.arguments[0]))
            elif n.member in self.methods and n.member.startswith('mUpdateTempUNIT'):
                if self.depth>3:raise Unknown('depth')
                self.depth+=1
                try:self.run(self.methods[n.member].body)
                except Return:pass
                finally:self.depth-=1
                return None
            elif n.member=='getString':raise Unknown('localized reference string')
            else:raise Unknown('format call '+str(n.member))
        else:return super().expr(n)
        for selector in getattr(n,'selectors',[]) or []:
            if not isinstance(selector,T.MethodInvocation):raise Unknown('selector')
            if selector.member=='append':r+=str(self.expr(selector.arguments[0]))
            elif selector.member=='toString':r=str(r)
            elif selector.member=='setText' and isinstance(r,tuple):self.output[r[1]]=str(self.expr(selector.arguments[0]))
            elif selector.member in ('setVisibility','setTextColor','setBackgroundResource'):pass
            else:raise Unknown('format selector '+selector.member)
        return r

def temperature_formats(profile,constants,env,methods):
    result={}
    for field,method,view in [('U_AIR_TEMP_LEFT','mUpdateAirTempLeft','air_sp_temp_left'),('U_AIR_TEMP_RIGHT','mUpdateTempRight','air_sp_temp_right')]:
        if field not in env or env[field]==255 or method not in methods:continue
        formats={}
        for unit in (0,1):
            observations=[];unit_text=None
            for raw in (80,81,100,101):
                f=FormatFacts(profile,constants,env,methods,raw,unit,field)
                try:
                    try:f.run(methods[method].body)
                    except Return:pass
                    text=f.output[view].replace('℃','').replace('℉','').replace('°C','').replace('°F','')
                    value=float(text)
                    labels=' '.join(v for k,v in f.output.items() if 'unit' in k)+f.output[view]
                    detected='C' if '℃' in labels or '°C' in labels else 'F' if '℉' in labels or '°F' in labels else None
                    if detected is None:raise Unknown('no explicit unit label')
                    if unit_text is not None and unit_text!=detected:raise Unknown('changing units')
                    unit_text=detected;observations.append((raw,value))
                except (Unknown,KeyError,ValueError,TypeError,ZeroDivisionError):observations=[];break
            if len(observations)==4:
                scale=observations[1][1]-observations[0][1];offset=observations[0][1]-80*scale
                if scale in (0.1,0.5,1.0,2.0) or abs(scale-0.1)<1e-6:
                    if all(abs(raw*scale+offset-value)<1e-6 for raw,value in observations):
                        formats[str(unit)]={'scale':round(scale,6),'offset':round(offset,6),'unit':unit_text}
        if formats:result[field]=formats
    return result
