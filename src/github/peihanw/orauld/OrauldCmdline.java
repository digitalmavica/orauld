package github.peihanw.orauld;

import static github.peihanw.ut.Stdout.*;

import java.nio.charset.Charset;

import github.peihanw.ut.PubMethod;
import github.peihanw.ut.Getopt;
import github.peihanw.ut.Stdout;
import java.io.BufferedReader;
import java.io.Console;
import java.io.FileReader;
import java.io.IOException;

public class OrauldCmdline {

	private static OrauldCmdline _Instance = null;
	public String _loginStr;
	public String _loginCfg;
	public String _querySql;
	public String _outputFnm;
	public String _delimiter;
	public int _wrkNum = 2;
	public int _verboseLevel = 3;
	public String _charset;
	public boolean _trimChar;
	public boolean _needReadPassword = false;
	public OrauldLoginRec _loginRec = new OrauldLoginRec();

	static {
		_Instance = new OrauldCmdline();
	}

	public OrauldCmdline() {
		_delimiter = "|";
	}

	public static OrauldCmdline GetInstance() {
		return _Instance;
	}

	public void init(String[] args) {
		Getopt g = new Getopt("sqluld", args, ":l:L:F:q:o:d:c:w:v:t");
		int c;
		while ((c = g.getopt()) != -1) {
			switch (c) {
				case 'L':
					_loginStr = g.getOptarg();
					break;
				case 'l':
					_loginStr = g.getOptarg();
					_needReadPassword = true;
					break;
				case 'F':
					_loginCfg = g.getOptarg();
					break;
				case 'q':
					_querySql = g.getOptarg();
					break;
				case 'o':
					_outputFnm = g.getOptarg();
					break;
				case 'd':
					_delimiter = g.getOptarg();
					break;
				case 'c':
					_charset = g.getOptarg();
					break;
				case 'w':
					_wrkNum = Integer.parseInt(g.getOptarg());
					if (_wrkNum <= 0) {
						_wrkNum = 1;
					} else if (_wrkNum > 4) {
						_wrkNum = 4;
					}
					break;
				case 'v':
					_verboseLevel = Integer.parseInt(g.getOptarg());
					if (_verboseLevel < 0 || _verboseLevel > 4) {
						_verboseLevel = 4;
					}
					break;
				case 't':
					_trimChar = true;
				case '?':
					break; // getopt() already printed an error
				default:
					Stdout.P(Stdout.WRN, "getopt() returned [%c]", c);
					break;
			}
		}

		if (PubMethod.IsEmpty(_querySql) || PubMethod.IsEmpty(_outputFnm)) {
			_usage(1);
		}

		if (PubMethod.IsEmpty(_charset)) {
			Charset dft_charset_ = Charset.defaultCharset();
			_charset = dft_charset_.name();
		}

		if (_needReadPassword) {
			Console console_ = System.console();
			if (console_ == null) {
				Stdout.P(Stdout.ERO, "can not get console object for read password");
				_usage(1);
			} else {
				_loginRec._password = new String(console_.readPassword("Please input password "));
			}
		}

		if (!_readLoginCfg()) {
			_usage(1);
		}
		if (!_parseLoginStr()) {
			_usage(1);
		}

		Stdout._DftLevel = _verboseLevel;
	}

	public void print() {
		P(DBG, "login_str [%s]", _loginStr);
		P(DBG, "query_sql [%s]", _querySql);
		P(DBG, "output_fnm [%s]", _outputFnm);
		P(DBG, "delimiter [%s]", _delimiter);
		P(DBG, "charset [%s]", _charset);
		P(DBG, "wrk_num %d", _wrkNum);
		P(DBG, "verbose %d", _verboseLevel);
		P(DBG, "trim %s", _trimChar ? "true" : "false");
	}

	private boolean _parseLoginStr() {
		if (_loginRec._password == null) {
			_loginRec._password = "";
		}
		_loginRec._host = "127.0.0.1";
		_loginRec._port = 1521;

		String[] fields_;
		if (_needReadPassword) {
			fields_ = _loginStr.split("[@:]");
			if (fields_.length < 2) {
				P(WRN, "login_str [%s] bad format", _loginStr);
				return false;
			}
			_loginRec._usr = fields_[0];
			_loginRec._sid = fields_[1];
			if (fields_.length > 2) {
				_loginRec._host = fields_[2];
			}
			if (fields_.length > 3) {
				_loginRec._port = Integer.parseInt(fields_[3]);
			}
		} else {
			fields_ = _loginStr.split("[/@:]");
			if (fields_.length < 3) {
				P(WRN, "login_str [%s] bad format", _loginStr);
				return false;
			}
			_loginRec._usr = fields_[0];
			_loginRec._password = fields_[1];
			_loginRec._sid = fields_[2];
			if (fields_.length > 3) {
				_loginRec._host = fields_[3];
			}
			if (fields_.length > 4) {
				_loginRec._port = Integer.parseInt(fields_[4]);
			}
		}
		return true;
	}

	private boolean _readLoginCfg() {
		if (_loginCfg == null || _loginCfg.isEmpty()) {
			return true;
		}
		BufferedReader br_ = null;
		try {
			br_ = new BufferedReader(new FileReader(_loginCfg));
			P(INF, "[%s] opened for parsing", _loginCfg);
			String raw_line_;
			int line_cnt_ = 0;
			while ((raw_line_ = br_.readLine()) != null) {
				++line_cnt_;
				if (raw_line_.startsWith("#")) {
					continue;
				}
				if (raw_line_.matches("^\\s*$")) {
					continue;
				}
				_loginStr = raw_line_;
				P(DBG, "_loginStr [%s] read from [%s] line [%d]", _loginStr, _loginCfg, line_cnt_);
				break;
			}
			if (_loginStr == null || _loginStr.isEmpty()) {
				P(WRN, "no login string read from [%s], total line scaned %d", _loginCfg, line_cnt_);
				return false;
			} else {
				return true;
			}
		} catch (Exception e) {
			P(ERO, e, "open [%s] for reading exception", _loginCfg);
			return false;
		} finally {
			try {
				if (br_ != null) {
					br_.close();
				}
			} catch (IOException ex) {
				// ignore
			}
		}
	}

	private void _usage(int jvm_exit_code) {
		String newline_ = String.format("%n");
		StringBuilder sb_ = new StringBuilder();
		sb_.append("Usage: -l conn_info -q query_sql -o output_fnm [-d delimiter] [-c charset] [-w wrk_num] [-v verbose] [-t]");
		sb_.append(newline_);
		sb_.append("Usage: -L login_str -q query_sql -o output_fnm [-d delimiter] [-c charset] [-w wrk_num] [-v verbose] [-t]");
		sb_.append(newline_);
		sb_.append("Usage: -F login_cfg -q query_sql -o output_fnm [-d delimiter] [-c charset] [-w wrk_num] [-v verbose] [-t]");
		sb_.append(newline_);
		sb_.append("eg   :        -l usr@sid:127.0.0.1:1521 -q \"select * from table_name\" -o uld.bcp");
		sb_.append(newline_);
		sb_.append("eg   : -L usr/passwd@sid:127.0.0.1:1521 -q \"select * from table_name\" -o uld.bcp");
		sb_.append(newline_);
		sb_.append("eg   : -F $HOME/etc/mytest_db_login.cfg -q \"select * from table_name\" -o uld.bcp");
		sb_.append(newline_);
		sb_.append("     : -l/-L : defalt host is 127.0.0.1, default port is 1521");
		sb_.append(newline_);
		sb_.append("     : -l : an interactive prompt will ask for password");
		sb_.append(newline_);
		sb_.append("     : -L : password is provided via command line args directly, bad guys may peek by list processes");
		sb_.append(newline_);
		sb_.append("     : -F : login_str is stored in the config file");
		sb_.append(newline_);
		sb_.append("     : -d : default delimiter is pipe char '|'");
		sb_.append(newline_);
		sb_.append("     : -c : default using JVM default encoding, support GB18030/UTF-8 etc.");
		sb_.append(newline_);
		sb_.append("     : -w : default 2, worker thread number, should between 1 and 4");
		sb_.append(newline_);
		sb_.append("     : -v : default 3, 0:ERO, 1:WRN, 2:INF, 3:DBG, 4:TRC");
		sb_.append(newline_);
		sb_.append("     : -t : default no trim for CHAR type");
		sb_.append(newline_);
		System.out.print(sb_.substring(0));

		if (jvm_exit_code >= 0) {
			System.exit(jvm_exit_code);
		}
	}
}
