package github.peihanw.orauld;

import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import github.peihanw.ut.PubMethod;
import static github.peihanw.ut.Stdout.*;
import java.util.regex.Matcher;

public class OrauldDmpRunnable implements Runnable {

	public long _dmpCnt;
	public long _splitCnt;
	public int _splitSeq;
	private final BlockingQueue<OrauldTuple>[] _dnQueues;
	private final boolean[] _eofs;
	private PrintWriter _pw;
	private boolean _terminateFlag = false;
	private OrauldCmdline _cmdline;
	private String _eor;

	public OrauldDmpRunnable(BlockingQueue<OrauldTuple>[] up_queues, BlockingQueue<OrauldTuple>[] dn_queues) {
		_dnQueues = dn_queues;
		_eofs = new boolean[_dnQueues.length];
		_cmdline = OrauldCmdline.GetInstance();
		if (PubMethod.IsEmpty(_cmdline._eorStr)) {
			_eor = String.format("%n");
		} else {
			_eor = String.format("%s%n", _cmdline._eorStr);
		}
	}

	public void setTerminateFlag() {
		_terminateFlag = true;
		P(INF, "_terminateFlag is set");
	}

	@Override
	public void run() {
		P(INF, "thread started");
		OrauldTuple tuple_;
		boolean end_ = false;
		int eof_cnt_ = 0;
		try {
			while (!end_) {
				for (int i = 0; i < _dnQueues.length; ++i) {
					if (_eofs[i]) {
						++eof_cnt_;
					} else {
						tuple_ = _pollTuple(_dnQueues[i]);
						if (_terminateFlag) {
							P(INF, "_terminateFlag detected, set end_ flag");
							end_ = true;
						}
						if (tuple_ != null) {
							if (tuple_.isEOF()) {
								P(INF, "EOF tuple detected, i=%d", i);
								_eofs[i] = true;
							} else {
								_dump(tuple_);
							}
						}
					}
				}
				if (eof_cnt_ >= _dnQueues.length) {
					P(INF, "%d of %d dn queues EOF", eof_cnt_, _dnQueues.length);
					break;
				}
				eof_cnt_ = 0;
			}
		} catch (Exception e) {
			P(WRN, e, "encounter exception, _dmpCnt=%d, re-throw as RuntimeException", _dmpCnt);
			throw new RuntimeException(e);
		} finally {
			PubMethod.Close(_pw);
		}
		P(INF, "thread ended, _dmpCnt=%d", _dmpCnt);
	}

	private OrauldTuple _pollTuple(BlockingQueue<OrauldTuple> queue) throws Exception {
		OrauldTuple tuple_;
		int idle_cnt_ = 0;
		while (true) {
			tuple_ = queue.poll(100, TimeUnit.MILLISECONDS);
			if (tuple_ == null) {
				++idle_cnt_;
				if (_terminateFlag) {
					P(INF, "_terminateFlag detected, idle_cnt_=%d", idle_cnt_);
					break;
				} else {
					if (idle_cnt_ > 6000) {
						P(WRN, "idle_cnt_=%d, throw timeout exception", idle_cnt_);
						throw new TimeoutException("dmp poll timeout");
					} else if (idle_cnt_ % 1000 == 0) {
						P(INF, "idle_cnt_=%d, please be noticed", idle_cnt_);
					}
					continue;
				}
			}
			break;
		}
		return tuple_;
	}

	private void _dump(OrauldTuple tuple) throws Exception {
		if (_pw == null) {
			_openPw();
		}
		if (tuple._joined != null) {
			_pw.print(tuple._joined);
		}
		_pw.print(_eor);
		_dmpCnt++;
		_splitCnt++;
		if (_dmpCnt % 100000 == 0) {
			P(DBG, "%,d records dumped", _dmpCnt);
		}
		if (_cmdline._splitLines > 0 && _splitCnt >= _cmdline._splitLines) {
			PubMethod.Close(_pw);
			_pw = null;
			_splitCnt = 0;
		}
	}

	private void _openPw() throws Exception {
		String bcp_fnm_ = _cmdline._bcpFnm;
		if (_cmdline._splitLines > 0) {
			String sfx_ = "";
			String pfx_ = bcp_fnm_;
			Matcher m = OrauldConst.PATTERN_FNM_SFX.matcher(bcp_fnm_);
			if (m.find()) {
				sfx_ = m.group();
				pfx_ = m.replaceAll("");
			}
			String seq_ = String.format("_%05d", ++_splitSeq);
			bcp_fnm_ = pfx_ + seq_ + sfx_;
		}
		FileOutputStream fos_ = new FileOutputStream(bcp_fnm_);
		OutputStreamWriter osw_;
		osw_ = new OutputStreamWriter(fos_, _cmdline._charset);
		_pw = new PrintWriter(osw_);
		P(INF, "%s opened for writing, charset [%s]", bcp_fnm_, _cmdline._charset);
		if (_cmdline._header) {
			_pw.println(_cmdline._headerLine);
		}
	}
}
