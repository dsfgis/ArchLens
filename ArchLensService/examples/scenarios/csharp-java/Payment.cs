using System.Text.Json.Serialization;
using System.Threading.Tasks;
// 合成迁移调查输入，不编译或执行。
public class Payment {
    [JsonPropertyName("total_amount")]
    public decimal Amount { get; set; }
    public ulong Id { get; set; }
    public async Task Submit() { await Task.Delay(1); }
}
