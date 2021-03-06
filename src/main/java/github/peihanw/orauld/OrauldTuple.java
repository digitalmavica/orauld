package github.peihanw.orauld;

import java.nio.ByteBuffer;
import java.sql.Clob;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import github.peihanw.ut.PubMethod;
import oracle.sql.NUMBER;
import oracle.sql.ZONEIDMAP;

import static github.peihanw.ut.Stdout.*;

// =  =  =  =  =  =  =  =  =  =  =  =  =  =  =  =  =  =  =  =  =  =  =  =  =
// explain by example
// 1.  assume a table like this:
//     col_num      number(9),
//     col_varchar2 varchar2(10),
//     col_date     date,
//     col_ts       timestamp
// 2.  cases
//     _cells[0] is just a padding field
// 2.1 EOF
//     _idx = -1, _cells = {null, ...}, _joined = null
//     * thread end
// 2.2 _idx is 0
//     _idx = 0,
//     _cells = {null, 123, 'word', 2014-08-01/01:23:45, 2014-08-01/01:23:45.678}
//     _joined = null
//     * concatenate/join all _cells, then output
// 2.3 _idx is _cells.length
//     _idx = 4
//     _cells = {null, null, null, null, null}
//     _joined = "123|word|20140801012345|20140801012345.678";
//     * output _joined directly
// 2.4 else: _idx > 0 && _idx < _cells.length
//     _idx = 2
//     _cells = {null, null, null, 2014-08-01/01:23:45, 2014-08-01/01:23:45.678}
//     _joined = "123|word";
//     * concatenate/join from _cells[_idx + 1], then output
// =  =  =  =  =  =  =  =  =  =  =  =  =  =  =  =  =  =  =  =  =  =  =  =  =
public class OrauldTuple {

	public int _idx;
	public Object[] _cells;
	public byte[][] _bytes;
	public String _joined;

	public OrauldTuple(int column_cnt) {
		_cells = new Object[column_cnt + 1];
		_bytes = new byte[column_cnt + 1][];
	}

	public boolean isEOF() {
		return _idx < 0 ? true : false;
	}

	public String join(StringBuilder sb, int[] column_types, String delimiter, boolean trim) throws SQLException {
		if (_idx >= _cells.length) {
			if (_joined == null) {
				P(WRN, "_idx is %d, _cells.length is %d, but _joined is null", _idx, _cells.length);
			}
			return _joined;
		} else if (_idx < 0) {
			P(INF, "_idx is %d, EOF detected", _idx);
			return null;
		} else {
			sb.delete(0, sb.length());
			if (_joined != null) {
				sb.append(_joined);
			}
			for (int i = _idx + 1; i <= _cells.length; i++) {
				addCell(sb, i, column_types, delimiter, trim);
			}
			return sb.substring(0);
		}
	}

	public void addCell(StringBuilder sb, int idx, int[] column_types, String delimiter, boolean trim) throws SQLException {
		if (idx > 1) {
			sb.append(delimiter);
		}
		if (_cells[idx] == null && _bytes[idx] == null) {
			return;
		}
		int column_type_ = column_types[idx];
		switch (column_type_) {
		case OrauldConst.ORA_TYPE_2_NUMBER: // java.math.BigDecimal
			if (_bytes[idx] != null) {
				NUMBER number_ = new NUMBER(_bytes[idx]);
				sb.append(number_.bigDecimalValue().toString());
			}
			break;
		case OrauldConst.ORA_TYPE_12_VARCHAR: // includes VARCHAR2
		case OrauldConst.ORA_TYPE_M8_ROWID:
			if (_bytes[idx] != null) {
				String varchar_ = new String(_bytes[idx]);
				sb.append(varchar_);
			}
			break;
		case OrauldConst.ORA_TYPE_1_CHAR:
			if (_bytes[idx] != null) {
				String char_ = new String(_bytes[idx]);
				sb.append(trim ? char_.trim() : char_);
			}
			break;
		case OrauldConst.ORA_TYPE_91_DATE:
		case OrauldConst.ORA_TYPE_93_TIMESTAMP:
		case OrauldConst.ORA_TYPE_M101_TIMESTAMPTZ:
		case OrauldConst.ORA_TYPE_M102_TIMESTAMPTZL:
			_cvtTimeCell(sb, column_type_, _bytes[idx]);
			break;
		case OrauldConst.ORA_TYPE_2005_CLOB: // cast oracle.sql.CLOB to java.sql.Clob
			if (_cells[idx] != null) {
				Clob clob_ = (Clob) _cells[idx];
				sb.append(clob_.getSubString(1, (int) clob_.length()));
			}
			break;
		case OrauldConst.ORA_TYPE_2004_BLOB: // just skip oracle.sql.BLOB
			break;
		default: // ?, Object
			if (_cells[idx] != null) {
				Object obj_ = _cells[idx];
				sb.append(obj_.toString());
			}
			break;
		}
	}

	private void _cvtTimeCell(StringBuilder sb, int column_type, byte[] guts) {
		if (guts == null || guts.length < 7) {
			return;
		}

		int cc_ = ((int) guts[0] & 0xff) - 100;
		int yy_ = ((int) guts[1] & 0xff) - 100;
		int mm_ = (int) guts[2];
		int dd_ = (int) guts[3];
		int hh_ = ((int) guts[4]) - 1;
		int mi_ = ((int) guts[5]) - 1;
		int ss_ = ((int) guts[6]) - 1;

		int nano_ = 0;
		if (guts.length >= 11) {
			nano_ = ByteBuffer.wrap(guts, 7, 4).getInt();
		}

		String tm_str_;
		if (column_type == OrauldConst.ORA_TYPE_91_DATE) {
			tm_str_ = String.format("%02d%02d-%02d-%02d %02d:%02d:%02d", cc_, yy_, mm_, dd_, hh_, mi_, ss_);
		} else {
			tm_str_ = String.format("%02d%02d-%02d-%02d %02d:%02d:%02d.%03d", cc_, yy_, mm_, dd_, hh_, mi_, ss_, nano_ / 1000000);
		}

		switch (column_type) {
		case OrauldConst.ORA_TYPE_M102_TIMESTAMPTZL:
			// assume DBTIMEZONE is UTC and SESSIONTIMEZONE is ZoneId.sysDefault()
			long utc_millis_ = PubMethod.Str2Time(tm_str_, PubMethod.TimeStrFmt.Fmt23).atZone(ZoneOffset.UTC).toInstant()
					.toEpochMilli();
			sb.append(PubMethod.Time2Str(LocalDateTime.ofInstant(Instant.ofEpochMilli(utc_millis_), PubMethod._LocTZ),
					PubMethod.TimeStrFmt.Fmt23));
			return;
		case OrauldConst.ORA_TYPE_M101_TIMESTAMPTZ:
			if (guts.length < 13) { // may happen
				sb.append(tm_str_);
				return;
			}
			break;
		default: // ORA_TYPE_91_DATE or ORA_TYPE_93_TIMESTAMP
			sb.append(tm_str_);
			return;
		}

		// ORA_TYPE_M101_TIMESTAMPTZ & guts.length >= 13
		long utc_millis_ = PubMethod.Str2Time(tm_str_, PubMethod.TimeStrFmt.Fmt23).atZone(ZoneOffset.UTC).toInstant()
				.toEpochMilli();
		if ((guts[11] & (byte) 0x80) == 0) { // (+/-)hh:mm
			int tz_hh_ = ((int) guts[11]) - 20;
			int tz_mm_ = ((int) guts[12]) - 60;
			long delta_millis_ = tz_hh_ * 3600000L + tz_mm_ * 60000L;
			sb.append(
					PubMethod.Time2Str(LocalDateTime.ofInstant(Instant.ofEpochMilli(utc_millis_ + delta_millis_), ZoneId.of("UTC")),
							PubMethod.TimeStrFmt.Fmt23));
			sb.append(' ');
			if (tz_hh_ >= 0) {
				sb.append('+');
			}
			sb.append(String.format("%02d:%02d", tz_hh_, tz_mm_));
		} else {
			int region_id_ = ((int) guts[11] & 0x03);
			region_id_ *= 64;
			region_id_ += ((int) guts[12] & 0xfc) / 4;
			String region_nm_ = ZONEIDMAP.getRegion(region_id_);
			ZoneId zone_id_ = null;
			if (PubMethod.IsBlank(region_nm_)) {
				region_nm_ = String.format("0x%02x%02x/%d", guts[11], guts[12], region_id_);
			} else {
				zone_id_ = ZoneId.of(region_nm_);
			}
			if (zone_id_ == null) {
				sb.append(tm_str_);
				sb.append(' ');
				sb.append(region_nm_);
			} else {
				ZoneOffset zone_offset_ = zone_id_.getRules().getOffset(Instant.ofEpochMilli(utc_millis_));
				long delta_millis_ = zone_offset_.getTotalSeconds() * 1000L;
				sb.append(PubMethod.Time2Str(
						LocalDateTime.ofInstant(Instant.ofEpochMilli(utc_millis_ + delta_millis_), ZoneId.of("UTC")),
						PubMethod.TimeStrFmt.Fmt23));
				sb.append(' ');
				sb.append(region_nm_);
			}
		}
	}
}
