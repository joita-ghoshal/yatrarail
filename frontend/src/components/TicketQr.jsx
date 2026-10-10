import { useEffect, useState } from 'react';
import QRCode from 'qrcode';

/**
 * Renders a QR payload the backend has already signed.
 *
 * Nothing here signs anything: the payload arrives signed and this component only draws it, so a
 * tampered or hand-crafted string simply fails verification at the gate. The rendered string is
 * also printed underneath the code, because a conductor with no scanner app should still be able
 * to read a reference aloud.
 */
export default function TicketQr({ payload, size = 208, reference }) {
  const [dataUrl, setDataUrl] = useState(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let live = true;
    if (!payload) return undefined;
    setFailed(false);
    QRCode.toDataURL(payload, { width: size * 2, margin: 1, errorCorrectionLevel: 'M' })
      .then((url) => { if (live) setDataUrl(url); })
      .catch(() => { if (live) { setDataUrl(null); setFailed(true); } });
    return () => { live = false; };
  }, [payload, size]);

  if (!payload) return null;

  return (
    <div className="ticket-qr">
      {dataUrl
        ? <img src={dataUrl} width={size} height={size} alt={`QR code for ticket ${reference || ''}`} />
        : (
          <div className="ticket-qr-missing" style={{ width: size, height: size }}>
            {failed ? 'QR code unavailable — show the reference below' : 'Generating code...'}
          </div>
        )}
      {reference && <div className="ticket-qr-ref">{reference}</div>}
    </div>
  );
}
