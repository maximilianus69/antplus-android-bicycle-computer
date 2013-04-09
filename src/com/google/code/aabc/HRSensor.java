package com.google.code.aabc;

//import com.dsi.ant.antplusdemo.AntPlusManager.ChannelStates;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.util.Log;
import android.util.FloatMath;

/**
 *  
 * @author floheigl
 *
 */
public class HRSensor implements SCSStream.ScsStartStoppable {
	
    /** The Log Tag. */
    public String TAG;
	
    // settings:
    
    /** ANT+ channel period for an HR Sensor
     *   data transmitted every i*8086/32768 seconds i=1,2 or 4
     *   remark: 32768 = 2^15       
     */
    private final short 	HR_PERIOD; 	/* i*8086 */   
    
    // system parameters
    private final int   MINHR = 30; /** Minimal measurable hr (bpm) */
    private final float EVENT_TIMEOUT = 2f*60f / (float)MINHR;
    
    private int			hrUserMax;
    private float 		smoothing;  /** size of smoothing window in seconds */
    
    private enum SENSORSTATE{
    	UNINIT,
    	RESET,
    	CURRENT,
    	ONEDROP, /* one heartbeat probably missed & corrected for */
    }
    private enum STARTSTOPSTATE{
    	START,
    	STOP
    }
    
    SENSORSTATE sensorState;
    STARTSTOPSTATE startstop;
    
    // from sensor: 
    //private int mAcumWheelRevs;
    private int mAcumBeats;
    private float mAcumTime;
	
    // for display / output
    private int 	hr 			= 0;
    private int		hrSmooth 	= 0;    
    private int		hrAvg 		= 0;
    private int 	hrMax		= 0;
    private int 	hrPct 		= 0;
    private int		hrSmoothPct	= 0;    
    private int		hrAvgPct	= 0;
     
    
    // timing for retransmission wait
    private float dataAge;
    
    /* the message stream, kept in an array used as ringbuffer */
    private int memsize;
    private HRMsg[] data;
    
    /* various indexes into this array */
    private int iCurr;		/* points to latest state change */
    private int iSmth;		/* smoothing index */
    

	public HRSensor(String TAG_, float smoothing_,int hrUserMax_, short HR_PERIOD_) {
		this.TAG = TAG_ + "HR: ";
		this.HR_PERIOD = HR_PERIOD_;
		this.smoothing = smoothing_;
		this.memsize = (int) Math.ceil(smoothing_/((float)HR_PERIOD/32768.0));
		this.hrUserMax= hrUserMax_;		
		
		this.data = new HRMsg[memsize];
		
		iCurr = -1;
		iSmth = 0;
		dataAge = 0;
		
		sensorState=SENSORSTATE.UNINIT;
		startstop=STARTSTOPSTATE.STOP;
		
	    mAcumBeats = 0;
	    mAcumTime = 0f;
	}
	/**
	 * Adds a new message to the stream and updates outputs accordingly
	 * @param ANTRxMessage the message from the scs being added 
	 */
	public void addMsg(byte[] ANTRxMessage){
		HRMsg msg = new HRMsg(ANTRxMessage);
		Log.i(TAG, "HRMsg:"+msg.toString());
		if(isNewEvent(msg)){
			handleNewEvent(msg); // compute stuff, reset indices
		} else {
			handleRetransmission(msg);
		}	
	}
	
	private boolean isNewEvent(HRMsg msg) {
		if(sensorState == SENSORSTATE.UNINIT){
			return true;
		}
		return(!msg.equals(data[iCurr]));
	}
	private void handleNewEvent(HRMsg msg) {
		Log.i(TAG, "handleNewEvent(): Start");
		Log.i(TAG, "NewHRMsg:"+msg.toString());
		dataAge = 0;
		
		hr	  = msg.getHRinst(); /* always display current hr (also in pause mode) */
		hrPct = Math.round(100*hr/hrUserMax);
		
		if( (sensorState == SENSORSTATE.UNINIT) || (sensorState == SENSORSTATE.RESET) ){
			iCurr = 0;
			data[0]=msg;
			iSmth = 0;
			sensorState = SENSORSTATE.CURRENT;
			return;
		}
		if(sensorState == SENSORSTATE.ONEDROP){
			sensorState = SENSORSTATE.CURRENT;
		}
//		if(startstop==STARTSTOPSTATE.STOP){
//			iCurr = 0;
//			data[0]=msg;
//			iSmth = 0;
//			/* the rest does not apply in pause mode */
//			return;
//		}
		
		HRMsg prev = data[iCurr];
		iCurr = (iCurr+1)%memsize;
		data[iCurr]=msg;
		HRMsg cur = data[iCurr];
		
		// update smoothing index
		if(iSmth == iCurr)
			iSmth=(iSmth+1)%memsize;;
		while(iSmth != iCurr){
			if (HRMsg.getDeltaTime(data[iSmth], cur) > smoothing){
				//too old, advance index
				iSmth = (iSmth+1)%memsize;
			} else { //ok as smoothing index
				break;
			}
		}		
		
		HRMsg smth = data[iSmth];
		
		int deltaBeatCnt = HRMsg.getDeltaBeatCnt(prev, cur);
		float deltaTime = HRMsg.getDeltaTime(prev, cur);
		
		/* Totals */
		mAcumBeats += deltaBeatCnt;
		mAcumTime += deltaTime;
		
		/* Heart Rate (max, avg, smooth) */
	    hrPct 		= Math.round(100*hr/hrUserMax);
	    hrSmooth 	= HRMsg.getHR(smth, cur);
	    hrSmoothPct	= Math.round(100*hrSmooth/hrUserMax);
	    
	    if(hr>hrMax)
	    	hrMax=hr;
	    
	    computeHrAvg();		
	}

	private void handleRetransmission(HRMsg msg) {
		
		dataAge += (float)HR_PERIOD/32768f;
		
		if (dataAge > EVENT_TIMEOUT){
			hr = 0;
			if(sensorState == SENSORSTATE.ONEDROP){
				// after one missed beat: no other beats detected, remove correction
				mAcumBeats--;
			}
			sensorState = SENSORSTATE.RESET;
			return;
		} else if (hr!=0 && sensorState != SENSORSTATE.ONEDROP){
			// ok, so let's see if the sensor (probably) missed a beat
			// we assume, the heart rate can't drop by more than p% from one beat to the next...
			float expNextTimeDelta = 60f/hr;
			if (expNextTimeDelta*1.5 < dataAge && dataAge < expNextTimeDelta*2){
				Log.i(TAG, "NewHRMsg: CORRECTION 1");
				mAcumBeats++; /* adjust hrAvg for individual misses */
				/* for longer dropouts, upper if is active */
				/* else mAcumTime gets incremented on next real beat */
				sensorState = SENSORSTATE.ONEDROP;
			}
			
		}
		if (dataAge > smoothing){
			hrSmooth = 0;
			hrSmoothPct = 0;
		}		
	}

	private void computeHrAvg() {
		
		if(mAcumTime > 0){
			hrAvg 		= Math.round(60*((float)mAcumBeats/mAcumTime));
		    hrAvgPct 		= Math.round(100*hrAvg/hrUserMax);		    
		} else{
			hrAvg = 0;
			hrAvgPct = 0;
		};
	}
	public boolean hasNewSignal() {
		return (dataAge==0);
	}
	public int getHr() {
		return hr;
	}
	public int getHrSmooth() {
		return hrSmooth;
	}
	public int getHrAvg() {
		return hrAvg;
	}
	public int getHrPct() {
		return hrPct;
	}
	public int getHrSmoothPct() {
		return hrSmoothPct;
	}
	public int getHrAvgPct() {
		return hrAvgPct;
	}
	public int getHrMax() {
		return hrMax;
	}
	public short getTripTimeHours() {
		return (short)FloatMath.floor(mAcumTime/3600);
	}
	public short getTripTimeMinutes() {
		return (short)(FloatMath.floor(mAcumTime/60)%60);
	}
	public short getTripTimeSeconds() {
		return (short)(Math.round(mAcumTime)%60);
	}
	public String getTripTime() {
		short min,sec;
		min = getTripTimeMinutes();
		sec = getTripTimeSeconds();
		
		return (getTripTimeHours()+":"
				+(min<10 ? "0"+min : min)+":"	
				+(sec<10 ? "0"+sec : sec));
	}	
	
	public void saveState(Editor editor) {
		editor.putInt("mAcumBeats",mAcumBeats);
		editor.putFloat("mAcumTime",mAcumTime);
		editor.putInt("hrMax",hrMax);	
	}
	public void loadConfiguration(SharedPreferences settings) {
		mAcumBeats = settings.getInt("mAcumBeats", 0);
		mAcumTime = settings.getFloat("mAcumTime", 0);
		hrMax = settings.getInt("hrMax", 0);
		computeHrAvg();
	}
	public void resetTotals() {
		mAcumBeats 	= 0;
		mAcumTime 	= 0;
		computeHrAvg();
		hrMax	= 0;		
	}
	@Override
	public void scsStart() {
		startstop=STARTSTOPSTATE.START;
		
	}
	@Override
	public void scsStop() {
		startstop=STARTSTOPSTATE.STOP;
		
	}
	
}

/*
 *  internal class HRMsg
 */

class HRMsg {
    public static final int  	TIME_ROLLOVER 	= 1<<16; // timeunit: 1/1024 seconds, rollover 64 seconds
    public static final int  	COUNT_ROLLOVER 	= 1<<8;
    public static final int  	TIME_FREQ 		= 1<<10;       /** 1 tick = 1/1024 seconds */
    
    
    protected int HRinst;
    protected int timeCnt;
	protected int beatCnt;	
	
	public HRMsg(byte[] ANTRxMessage) {
		super();		
		this.timeCnt = (int)(((ANTRxMessage[8] & 0xFF)<<8) | ((ANTRxMessage[7] & 0xFF)));
		this.beatCnt = ANTRxMessage[9] & 0xFF;
		this.HRinst = ANTRxMessage[10] & 0xFF;
	}
	
	public HRMsg getFakeSuccessor(float deltaTime) {
		HRMsg ret = new HRMsg(this);
		ret.beatCnt++;
		ret.timeCnt = timeCnt + Math.round(deltaTime*TIME_FREQ);
		return ret;
	}

	public HRMsg(HRMsg orig) {
		super();		
		this.HRinst = orig.HRinst;
   	 	this.timeCnt = orig.timeCnt;
   	 	this.beatCnt = orig.beatCnt;
	}


	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + HRinst;
		result = prime * result + timeCnt;
		result = prime * result + beatCnt;
		return result;
	}


	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		HRMsg other = (HRMsg) obj;
		if (HRinst != other.HRinst)
			return false;
		if (timeCnt != other.timeCnt)
			return false;
		if (beatCnt != other.beatCnt)
			return false;
		return true;
	}


	public int getTimeCnt() {
		return timeCnt;
	}

	public int getBeatCnt() {
		return beatCnt;
	}
	public int getHRinst() {
		return HRinst;
	}
	
	/**
	 * 
	 * @param a first sample
	 * @param b second sample, within 64 seconds of first sample
	 * @return the average HR between the two samples in beats per minute
	 */
	public static int getHR(HRMsg a, HRMsg b){
		int hr;
		/* Wheel Revolutions & Speed*/
		int deltaBeats = getDeltaBeatCnt(a,b);
		float deltaTime = getDeltaTime(a,b);
		
		// speed
		if(deltaTime>0.0000001){
			hr =  Math.round(60*((float)deltaBeats/deltaTime));
		} else {
			hr = 0; //should not happen method only called on change
		}
		return hr;
	}


	public static int getDeltaBeatCnt(HRMsg a, HRMsg b) {
		int deltaBeatCnt = b.getBeatCnt() - a.getBeatCnt();
		if (deltaBeatCnt < 0)
			deltaBeatCnt += COUNT_ROLLOVER;
		return deltaBeatCnt;
	}
	/**
	 * @return the time difference between two samples in seconds
	 */
	public static float getDeltaTime(HRMsg a, HRMsg b) {
		int deltaTimeCnt = b.getTimeCnt() - a.getTimeCnt();
		if (deltaTimeCnt < 0)
			deltaTimeCnt += TIME_ROLLOVER;
		return ((float)deltaTimeCnt)/TIME_FREQ;
	}

	@Override
	public String toString() {
		return "HRMsg [HRinst=" + HRinst + ", timeCnt=" + timeCnt
				+ ", beatCnt=" + beatCnt + "]";
	}

}
