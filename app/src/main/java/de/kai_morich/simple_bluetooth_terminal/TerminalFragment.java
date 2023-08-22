package de.kai_morich.simple_bluetooth_terminal;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.IBinder;
import android.text.Editable;
import android.text.Layout;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.method.ScrollingMovementMethod;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.AlignmentSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;

public class TerminalFragment extends Fragment implements ServiceConnection, SerialListener {

    private enum Connected {False, Pending, True}

    private String deviceAddress;
    private SerialService service;

    private TextView receiveText;
    private TextView sendText;
    private TextUtil.HexWatcher hexWatcher;

    private Connected connected = Connected.False;
    private boolean initialStart = true;
    private boolean hexEnabled = false;
    private boolean pendingNewline = false;
    private String newline = TextUtil.newline_crlf, time = "";
    private boolean WaitingDelivery = false;


    /*
     * Lifecycle
     */
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        setRetainInstance(true);
        deviceAddress = getArguments().getString("device");
    }

    @Override
    public void onDestroy() {
        if (connected != Connected.False)
            disconnect();
        getActivity().stopService(new Intent(getActivity(), SerialService.class));
        super.onDestroy();
    }

    @Override
    public void onStart() {
        super.onStart();
        if (service != null)
            service.attach(this);
        else
            getActivity().startService(new Intent(getActivity(), SerialService.class)); // prevents service destroy on unbind from recreated activity caused by orientation change
    }

    @Override
    public void onStop() {
        if (service != null && !getActivity().isChangingConfigurations())
            service.detach();
        super.onStop();
    }

    @SuppressWarnings("deprecation")
    // onAttach(context) was added with API 23. onAttach(activity) works for all API versions
    @Override
    public void onAttach(@NonNull Activity activity) {
        super.onAttach(activity);
        getActivity().bindService(new Intent(getActivity(), SerialService.class), this, Context.BIND_AUTO_CREATE);
    }

    @Override
    public void onDetach() {
        try {
            getActivity().unbindService(this);
        } catch (Exception ignored) {
        }
        super.onDetach();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (initialStart && service != null) {
            initialStart = false;
            getActivity().runOnUiThread(this::connect);
        }
        if (MySingletonClass.getInstance().gete22Change()) {
            send("@#config>A" + String.valueOf(MySingletonClass.getInstance().getairDataRate()) + "A", false);
            MySingletonClass.getInstance().setE22change(false);
        }
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
        service = ((SerialService.SerialBinder) binder).getService();
        service.attach(this);
        if (initialStart && isResumed()) {
            initialStart = false;
            getActivity().runOnUiThread(this::connect);
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        service = null;
    }

    /*
     * UI
     */
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_terminal, container, false);
        receiveText = view.findViewById(R.id.receive_text);                          // TextView performance decreases with number of spans
        receiveText.setTextColor(getResources().getColor(R.color.colorRecieveText)); // set as default color to reduce number of spans
        receiveText.setMovementMethod(ScrollingMovementMethod.getInstance());

        sendText = view.findViewById(R.id.send_text);
        hexWatcher = new TextUtil.HexWatcher(sendText);
        hexWatcher.enable(hexEnabled);
        sendText.addTextChangedListener(hexWatcher);
        sendText.setHint(hexEnabled ? "HEX mode" : "");

        View sendBtn = view.findViewById(R.id.send_btn);
        sendBtn.setOnClickListener(v -> send(sendText.getText().toString(), true));
        return view;
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, MenuInflater inflater) {


        MenuItem configChange = menu.add("Change E22 config");
        configChange.setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem menuItem) {
                Intent intent = new Intent(getActivity().getApplicationContext(), ChangeConfig.class);
                startActivity(intent);

                return true;
            }
        });

    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.clear) {
            receiveText.setText("");
            return true;
        } else if (id == R.id.newline) {
            String[] newlineNames = getResources().getStringArray(R.array.newline_names);
            String[] newlineValues = getResources().getStringArray(R.array.newline_values);
            int pos = java.util.Arrays.asList(newlineValues).indexOf(newline);
            AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
            builder.setTitle("Newline");
            builder.setSingleChoiceItems(newlineNames, pos, (dialog, item1) -> {
                newline = newlineValues[item1];
                dialog.dismiss();
            });
            builder.create().show();
            return true;
        } else if (id == R.id.hex) {
            hexEnabled = !hexEnabled;
            sendText.setText("");
            hexWatcher.enable(hexEnabled);
            sendText.setHint(hexEnabled ? "HEX mode" : "");
            item.setChecked(hexEnabled);
            return true;
        } else {
            return super.onOptionsItemSelected(item);
        }
    }

    /*
     * Serial + UI
     */
    private void connect() {
        try {
            BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
            BluetoothDevice device = bluetoothAdapter.getRemoteDevice(deviceAddress);
            status("connecting...");
            connected = Connected.Pending;
            SerialSocket socket = new SerialSocket(getActivity().getApplicationContext(), device);
            service.connect(socket);
        } catch (Exception e) {
            onSerialConnectError(e);
        }
    }

    private void disconnect() {
        connected = Connected.False;
        service.disconnect();
    }

    private void send(String str, boolean show) {
        if (connected != Connected.True) {
            Toast.makeText(getActivity(), "not connected", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!str.equals("")) {
            try {
                String msg;
                byte[] data;
                if (hexEnabled) {
                    StringBuilder sb = new StringBuilder();
                    TextUtil.toHexString(sb, TextUtil.fromHexString(str));
                    TextUtil.toHexString(sb, newline.getBytes());
                    msg = sb.toString();
                    data = TextUtil.fromHexString(msg);
                } else {
                    msg = str;
                    data = ("\"" + str + newline).getBytes();
                }
                SpannableStringBuilder spn = new SpannableStringBuilder(msg + '\n');
                spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorSendText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                spn.setSpan(new AbsoluteSizeSpan(60), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                spn.setSpan(new AlignmentSpan.Standard(Layout.Alignment.ALIGN_RIGHT), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
//                spn.setSpan(new StyleSpan(Typeface.BOLD), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                if (show) {
                    receiveText.append(spn);
                    time = new SimpleDateFormat("HH:mm").format(new Date());
                    spn.clear();
                    spn.append(time + "\n");
                    spn.setSpan(new AlignmentSpan.Standard(Layout.Alignment.ALIGN_RIGHT), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    spn.setSpan(new AbsoluteSizeSpan(25), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorTimeText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    receiveText.append(spn);
                    WaitingDelivery = true;
                    sendText.setText("");
                    new CountDownTimer(50250, 8250) {
                        public void onFinish() {
                            // When timer is finished
                            // Execute your code here
                        }

                        public void onTick(long millisUntilFinished) {
                            // millisUntilFinished    The amount of time until finished.
                            try {
                                if (WaitingDelivery)
                                    service.write(data);
                            } catch (Exception e) {
                                onSerialIoError(e);
                            }
                        }
                    }.start();
                } else service.write(data);
            } catch (Exception e) {
                onSerialIoError(e);
            }
        }
    }

    private void receive(ArrayDeque<byte[]> datas) {
        String receiveMsg = null;
        SpannableStringBuilder spn = new SpannableStringBuilder();
        for (byte[] data : datas) {
            if (hexEnabled) {
                spn.append(TextUtil.toHexString(data)).append('\n');
            } else {
                String msg = new String(data);
                receiveMsg = msg;
                if (newline.equals(TextUtil.newline_crlf) && msg.length() > 0) {
                    // don't show CR as ^M if directly before LF
                    msg = msg.replace(TextUtil.newline_crlf, TextUtil.newline_lf);
                    // special handling if CR and LF come in separate fragments
                    if (pendingNewline && msg.charAt(0) == '\n') {
                        if (spn.length() >= 2) {
                            spn.delete(spn.length() - 2, spn.length());
                        } else {
                            Editable edt = receiveText.getEditableText();
                            if (edt != null && edt.length() >= 2)
                                edt.delete(edt.length() - 2, edt.length());
                        }
                    }
                    pendingNewline = msg.charAt(msg.length() - 1) == '\r';
                }
                if (receiveMsg.startsWith("\"") && receiveMsg.endsWith("\r\n")) {
                    msg = msg.replaceFirst("\"", "");
                    spn.append(TextUtil.toCaretString(msg, newline.length() != 0));
                }

            }
        }
        if (receiveMsg.startsWith("\"") && receiveMsg.endsWith("\r\n")) {
            receiveMsg = receiveMsg.replaceFirst("\"", "");
            if (receiveMsg.contains("@#ok")) {
                MediaPlayer sentSound = MediaPlayer.create(getActivity().getApplicationContext(), R.raw.sent);
                sentSound.start();
                spn.clear();
                spn.append("✓\n");
                spn.setSpan(new AlignmentSpan.Standard(Layout.Alignment.ALIGN_RIGHT), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                spn.setSpan(new AbsoluteSizeSpan(20), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorSendText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                receiveText.append(spn);
            } else if (receiveMsg.contains("@#delivery")) {
                if (WaitingDelivery) {
                    MediaPlayer deliverySound = MediaPlayer.create(getActivity().getApplicationContext(), R.raw.delivery);
                    deliverySound.start();
                    spn.clear();
                    spn.append("✓\n");
                    spn.setSpan(new AbsoluteSizeSpan(20), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    spn.setSpan(new AlignmentSpan.Standard(Layout.Alignment.ALIGN_RIGHT), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorRecieveText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    receiveText.append(spn);
                    WaitingDelivery = false;
                }
            } else if (receiveMsg.contains("@#configok")) {
                receiveText.append("Config Changed\r\n");
            } else {
                MediaPlayer recieveSound = MediaPlayer.create(getActivity().getApplicationContext(), R.raw.recieve);
                recieveSound.start();
                spn.setSpan(new AbsoluteSizeSpan(60), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                spn.setSpan(new AlignmentSpan.Standard(Layout.Alignment.ALIGN_LEFT), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                receiveText.append(spn);
                time = new SimpleDateFormat("HH:mm").format(new Date());
                spn.clear();
                spn.append(time + "\n");
                spn.setSpan(new AlignmentSpan.Standard(Layout.Alignment.ALIGN_LEFT), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                spn.setSpan(new AbsoluteSizeSpan(25), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorTimeText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                receiveText.append(spn);

                new CountDownTimer(1250, 250) {
                    public void onFinish() {
                        // When timer is finished
                        // Execute your code here
                    }

                    public void onTick(long millisUntilFinished) {
                        // millisUntilFinished    The amount of time until finished.
                        send("\"@#delivery", false);
                    }
                }.start();

            }
        }
    }

    private void status(String str) {
        SpannableStringBuilder spn = new SpannableStringBuilder(str + '\n');
        spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorStatusText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        receiveText.append(spn);
    }

    /*
     * SerialListener
     */
    @Override
    public void onSerialConnect() {
        status("connected");
        connected = Connected.True;
    }

    @Override
    public void onSerialConnectError(Exception e) {
        status("connection failed: " + e.getMessage());
        disconnect();
        getActivity().onBackPressed();
    }

    @Override
    public void onSerialRead(byte[] data) {
        ArrayDeque<byte[]> datas = new ArrayDeque<>();
        datas.add(data);
        receive(datas);
    }

    public void onSerialRead(ArrayDeque<byte[]> datas) {
        receive(datas);
    }

    @Override
    public void onSerialIoError(Exception e) {
        status("connection lost: " + e.getMessage());
        disconnect();
        getActivity().onBackPressed();

    }

}
